// scmonome_pitchlattice
//
// test application using the MonomeGridApp template class in a generic fashion.
// this is a sort of weird just-intonation keyboard,
// or maybe more like an additive synth...

// the top rows are notes. button press duration -> fade time, fade in/out on button lift.

// the bottom two rows store and recall combinations of notes.
// touch one of these to store the currently playing notes, if that state is empty,
// or recall a previous state, if already stored.
// hold a state button and play notes to change the stored state.

// emb@catfact.net 

~lattice = MonomeGridApp.new(

	/// important stuff
	server:Server.local,
	name:"scmonome_sinelattice",
	bounds:Rect(100, Window.screenBounds.height - 100, 260, 110),
	
	// target grid coordinates
	width:8,
	height:8,
	xOffset:0,
	yOffset:0,
	
	guiInitAction:{ arg app;
		// TODO: make some synth controls:
		// amp / limiting
		// envelope curve
		// timbre
		// fundamental frequency
		// lattice control
///		//app.ctx.latticeText = StaticText(app.window, 260@300).text_ ... etc
		// noteStorage storage!
	},
	
	resizeAction:{ arg app;
		// TODO: need to rebuild all the noteState and noteStorage data when resizing the app.
		// for now, just don't change the size after creation. how hard is that?
	},
	
	audioInitAction:{ arg app;
		// a synth definition
		SynthDef.new(\sineEnv, {
			arg out=0, hz=220, level=0.25,
				attack=1.0, release=1.0, curve = \lin,
				gate=1, pan=0.0;
			var amp;
			amp = EnvGen.ar(Env.asr(attack, 1.0, release, curve), gate:gate, doneAction:2);
			amp = amp * AmpCompA.kr(hz, 30);
			Out.ar(out, Pan2.ar(SinOsc.ar(hz) * amp * level, pan));
		}).send(app.server);
	},
	
	monomeInitAction:{ arg app;
		//----- functions
		// build the pitch lattice given x and y ratio sets. easier to see than to explain
		app.ctx.put(\buildLattice, { arg px, py;
			var oct = 1.0;
			Array.fill(app.width * app.height, { arg i;
				if ((i % px.size) == 0, {
					oct = oct * py.wrapAt(i);
				});
				app.ctx.fundamental * px.wrapAt(i) * oct
			}).clump(app.width).flop.postln;
		});
		// process a note change for given coordinates and state
		app.ctx.put(\performNote, { arg x, y;
	//		postln("performing");
			if(app.ctx.noteState[x][y].state == 0, {
				app.ctx.noteState[x][y].state = 1;
				// re-trigger if already playing
				if(app.ctx.sine_s[x][y].notNil && app.ctx.sine_s[x][y].isPlaying, {
					app.ctx.sine_s[x][y].set(\release, app.ctx.noteState[x][y].time);
					app.ctx.sine_s[x][y].set(\gate, 0);
				});
				app.ctx.sine_s[x][y] = Synth.new(\sineEnv, [
					\attack, app.ctx.noteState[x][y].time,
					\release, app.ctx.noteState[x][y].time,
					\hz, app.ctx.hz[x][y]
				], app.server);
				NodeWatcher.register(app.ctx.sine_s[x][y]);
			}, {
				if(app.ctx.sine_s[x][y].notNil && app.ctx.sine_s[x][y].isPlaying, {
					if (app.ctx.noteState[x][y].time > 0.0, {app.ctx.sine_s[x][y].set(\release, app.ctx.noteState[x][y].time); });
					app.ctx.sine_s[x][y].set(\gate, 0);
				});
				app.ctx.noteState[x][y].state = 0;
			});		
			app.msg('/grid/led/set', app.getDeviceCoordinates(x, y) ++ app.ctx.noteState[x][y].state);
		});
		
		//----- runtime variables
		// timers
		app.ctx.timers = Array.fill(app.width, { Array.fill(app.height-2, { SystemClock.seconds }); });
		// current note state of the whole grid
		app.ctx.noteState = Array.fill(app.width, { Array.fill(app.height-2, { (\state:0, \time:0.0) }); });
		// note storage array.. this contains (width * 2) noteStates
		app.ctx.noteStorage = Array.fill(app.width * 2, { Array.fill(app.width, {Array.fill(app.height - 2, { Event.new })})});
		// synths
		app.ctx.sine_s = Array.fill(app.width, { Array.fill(app.height-2, { nil }); });
		
		///////////// experiment with these....!
		///// the "lattice" is just a sequence of pitches.
		///// it is produced by repeating the values of fX at successive powers of the values of fY.
		///// so, for an 8-tone major scale (plus tritone), starting on c, repeated at the octave, use:
		///// fundamental = 24.midicps;
		///// fX = [0, 2, 4, 5, 6, 7, 8, 10].midiratio;
		///// fY = [2.0];
		///// by default, here is something more exotic:
		// fundamental pitch
		app.ctx.fundamental = 30;
		// frequency ratio x
		app.ctx.fX = [1, 9/8, 6/5, 4/3];
		// pitch ratio y;
		app.ctx.fY = [3/2, 4/5];
		////////////////////
		
		// pitch lattice
		app.ctx.hz = app.ctx[\buildLattice].value(app.ctx.fX, app.ctx.fY);
		// state-button-pressed flags
		app.ctx.stateButtonPressed = Array.fill(app.width * 2, {false});
		// key responder
		app.ctx.keyResponder = MonomeResponder.new(
			filter:(
				\id:{arg id; id.asSymbol == app.id.asSymbol},
				\cmd:'/grid/key'
			),
			function:{ arg r, e;
				var x, y, keyState, stateIdx;
				//e.postln
				#x, y = app.getLocalCoordinates(e.a, e.b);
				keyState = e.c;
				if (x.isNil || y.isNil, {
					postln("/grid/key responder: key press out of bounds.");
				}, {
					if (y > (app.height - 3), { 
					 	// bottom 2 rows: store/recall/alter note combinations
						stateIdx = x + ((y - (app.height -2 )) * app.width);
					 	if (keyState > 0, {
						 	app.ctx.stateButtonPressed[stateIdx] = true;
						 	// if the designated state is empty, copy the current note state
						 	if (app.ctx.noteStorage[stateIdx].every({ arg cols; cols.every({ arg rowE; rowE.isEmpty }) }), {
							 	postln("storing " ++ stateIdx);
							 	app.ctx.noteState.do({ arg col; "        ".post; col.postln; });
				
							 	app.ctx.noteStorage[stateIdx].do({arg col, i; col.do({ arg row, j;
								 	app.ctx.noteStorage[stateIdx][i][j].state = app.ctx.noteState[i][j].state;
								 	app.ctx.noteStorage[stateIdx][i][j].time = app.ctx.noteState[i][j].time;
								 }); });

							 	"stored: ".postln;
							 	app.ctx.noteStorage[stateIdx].do({ arg col; "        ".post; col.postln; });
							 	app.msg('/grid/led/set', app.getDeviceCoordinates(stateIdx % app.width, app.height - 2 + (stateIdx / app.width).floor) ++ 1);
						 	}, {
							 	postln("recalling " ++ stateIdx);
							 	// if the state is not empty, recall it
							 	app.ctx.noteStorage[stateIdx].do({ arg cols, i; cols.do({ arg rowE, j; 
								 	[i, j, rowE].postln;
								 	app.ctx.noteState[i][j].time = rowE.time; 
								 	app.ctx.noteState[i][j].state = 1 - rowE.state;
								 	app.ctx[\performNote].value(i, j);
							 	}); });
						 	});
					 	}, {
						 	app.ctx.stateButtonPressed[stateIdx] = false;
					 	});
					}, { // upper rows: notes
						if(keyState > 0, {
							// on key press, start the corresponding timer
							app.ctx.timers[x][y] = SystemClock.seconds;
						}, {				
							// on key lift, poll the timer, perform a note (on or off), and store the state if appropriate
							app.ctx.timers[x][y] = SystemClock.seconds - app.ctx.timers[x][y];
							app.ctx.noteState[x][y].time = app.ctx.timers[x][y];
							app.ctx[\performNote].value(x, y);
							app.ctx.stateButtonPressed.do({ arg pressed, i;
								if(pressed, {
									app.ctx.noteStorage[i][x][y].time = app.ctx.noteState[x][y].time;
									app.ctx.noteStorage[i][x][y].state = app.ctx.noteState[x][y].state;
								});
							});
						});
					});
				});
			}
		);
		
		// deal with focus change when we lose the port on this device
		// TODO: i guess MonomeClient needs multiple functions for multiple devices... blech
		MonomeClient.lostPortFunction = { arg id; if(id.asSymbol == app.id.asSymbol, { app.focus_(0); }); };
		MonomeClient.lostPrefixFunction = { arg prefix; postln("lost "++prefix); if(prefix.asSymbol == app.prefix.asSymbol, {
			app.id_(nil);
			app.focus_(0);
			app.prefixField.string_("?");
		}); };
		
				
		Routine {
			1.0.wait;
			app.recallConnection;
			1.0.wait;
			app.msg('/grid/led/all', [0]);	
		}.play;
	},
	
	killAction:{ arg app;
		app.ctx.keyResponder.remove;
	}	
);
