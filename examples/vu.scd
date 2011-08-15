

// SCVU 
//
// test of MonomeClient framework with serialosc 1.0
//
// a scrolling VU meter, yay!
//
// but really, the point is to test interactions (port stealing) with other apps,
// so most of this is just an attempt towards sort of boilerplate monome application code.
// stick different stuff in the SynthDef and the MonomeResponder and you're good to go...
//
// emb@catfact.net

Routine {
	var server;			// the server!
	var init; 			// list of init conditions
	var trig_s;			// Synth to send amplitude
	var trig_r;			// OSC responder to listen to it
	var key_r;			// MonomeResponder for key presses
	var prefix;			// app's prefix
	var focusedDevices;	// app's focused device id's
	var scroll;			// scroll data
	
	// keep track of focused devices as an ordered set of id's. 
	focusedDevices = OrderedIdentitySet.new;
	
	// make a probably-unique prefix string
	prefix = ("/scvu"++Date.getDate.stamp).asSymbol;
	
	// Conditions are handy for hanging a thread while things initialize
	init = (\monomeConnect:Condition(false), \serverBoot:Condition(false););
	
	// boot the local audio server
	server = Server.local.waitForBoot { init.serverBoot.test = true; init.serverBoot.signal; };
	
	init.serverBoot.wait;
	
	// define and create a  Synth that sends amplitudes
	trig_s = SynthDef.new(\amp_sendtrig, {
		arg id, in=0, attack=0.02, release=0.06, gain=1.0, speed=10;
		var pulse, amp;
		pulse = Impulse.kr(speed);
		amp = Amplitude.kr(SoundIn.ar(in) * gain, attack, release);
		SendTrig.kr(pulse, id, amp);
	}).play(server, [\attack, 0.001, \release, 2.0, \gain, (6.dbamp), \speed, 18]);

	// a data structure for the scroll; it shall be overly huge to accomodate theoretical monsternomes
	scroll = List.fill(2048, {0});

	// make an OSCresponder to listen for amplitudes, light up whatever devices are connected
	trig_r = OSCresponderNode(server.addr, '/tr', { |t, r, msg|
		if(msg[1] == trig_s.nodeID, {
			focusedDevices.do({ arg id;
				var w, h, level, dev;
				dev = MonomeClient.devices[id];
				w = dev.data['/sys/size'][0];
				h = dev.data['/sys/size'][1];
				// map level to db and set a whole column (regardless of size)
				level = msg[3].ampdb.linlin(-30.0, 0.0, 0.0, h + 0.99).floor;
				// stick it in the scroll
				scroll.addFirst(level);
				scroll.pop;
				// postln([w] ++ scroll.copySeries(0, 1, 8));
				// display the last (w) entries in the scroll
				w.do({ arg i;
					h.do({ arg j;	
						dev.msg('/grid/led/set', i, h-j-1, if(j < scroll[i], {1}, {0}));
					});
				});
			});
		});
	}).add;
	
	// make a MonomeResponder to see key presses from any monomes, pause the synth if pressed
	key_r = MonomeResponder.new(
		 filter:(\cmd:'/grid/key'),
		 function:{
			 arg r, e; 
			 trig_s.run(e.c == 0);
		 }
	);
	
	//// make a gui to toggle connections and rotation
	// (wrap GUI stuff in a function and defer to AppClock or it will hurl)
	{
		var win; 								// a window!
		var vl, hl; 							// flow layouts
		var wW, wH, wX=200, wY=800;				// coordinates
		var butSyms = [\connect, \rotate];		// keys for identifying buttons
		var buts = MultiLevelIdentityDictionary.new;	// tree of buttons
		var buildConnectionWindow;	
		// function to scan devices and build the connection window:
		buildConnectionWindow = { Routine {
					
			// read .conf files
			MonomeClient.scanDevices;
			// attempt to contact each device on file
			MonomeClient.connectAllDevices(doneAction:{ init.monomeConnect.test = true; init.monomeConnect.signal; });
			init.monomeConnect.test = false; init.monomeConnect.wait;
			
			// layout components
			hl = List.new;
			wW = 400;
			wH = MonomeClient.getConnectedDevices.size * 50 + 40;
			win = Window.new("SCVU", Rect(wX, wY, wW, wH));
			vl = VLayoutView(win, Rect(0, 0, wW, wH));
			// top row of controls
			hl.add(HLayoutView(vl, Rect(0, 0, wW, 30)));
			// text
			StaticText(hl.last, Rect(0, 0, 350, 30)).string_(" DEVICES:");
			// a button to rescan devices and rebuild this windows
			Button(HLayoutView(hl.last, Rect(0, 0, wW, 30)), Rect(0, 0, 50, 30))
				.states_([["rescan", Color.white, Color.new(1.0, 0.5, 0.5)]])
				.action_({ 
					wX = win.bounds.left;
					wY = win.bounds.top;
					win.close;
					buildConnectionWindow.value;
				}
			);
			// connection and rotation buttons for each device
			MonomeClient.getConnectedDevices.do({ arg dev;
				hl.add(HLayoutView(vl, Rect(0, 0, wW, 50)));
				butSyms.do({ arg sym;
					buts.put(dev.id, sym, Button(hl.last, Rect(0, 0, 200, 50)));
					sym.switch(
						\connect, {
							buts[dev.id][sym].states = [
								["  " ++ dev.id.asString ++ " : disconnected", Color.new(0.3, 0.3, 0.3), Color.new(0.7, 0.7, 0.7)],
								["  " ++ dev.id.asString ++ " : connected", Color.black, Color.new(0.4, 0.7, 0.5)]
							];
							buts[dev.id][sym].action = { arg but;
								if(focusedDevices.includes(dev.id), {
									MonomeClient.restoreDevicePort(dev.id);
									MonomeClient.restoreDevicePrefix(dev.id);
									focusedDevices.remove(dev.id);
									but.value(0);
								}, {
									MonomeClient.setDevicePrefix(dev.id, prefix);
									MonomeClient.stealDevicePort(dev.id);
									focusedDevices.add(dev.id);
									but.value(1);
								});
							};
						}, 
						\rotate, {
							buts[dev.id][sym].states = [0, 90, 180, 270].collect({ arg angle, i;
								["rotation: " ++ angle, Color.black, Color.gray((angle + 130) / 400)];
							});
							buts[dev.id][sym].action = { arg but;
								but.value.switch(
									0, { dev.msg('/sys/rotation', 0); },
									1, { dev.msg('/sys/rotation', 90); },
									2, { dev.msg('/sys/rotation', 180); },
									3, { dev.msg('/sys/rotation', 270); }
								);
							};	
						}
					);
				});
			});
			focusedDevices.do({ arg id;
				buts[id][\connect].value(1);		
			});
			win.onClose = {
				focusedDevices.do({ arg id;			
					MonomeClient.restoreDevicePort(id);
					MonomeClient.restoreDevicePrefix(id);
					focusedDevices.remove(id);
				});
			};
			win.front;
		}.play(AppClock) };
		
		buildConnectionWindow.value;
					
		// and finally, edit the lostPort and lostPrefix functions
		MonomeClient.lostPortFunction = {
			arg id;
			if (focusedDevices.includes(id), {
				focusedDevices.remove(id);
			});
			defer {
				buts[id][\connect].value = 0;
			};
		};
		
		MonomeClient.lostPrefixFunction = {
			arg id;
			if (focusedDevices.includes(id), {
				focusedDevices.remove(id);
			});
			defer {
				buts[id][\connect].value = 0;
			};
		};
		
	}.defer;
	
}.play;
 
 
/*
// debug
thisProcess.recvOSCfunc = { |time, addr, msg| 
	if(msg[0] != '/status.reply' && msg[0] != '/tr') {
		"time: % sender: %\nmessage: %\n".postf(time, addr, msg); 
	}  
};

*/