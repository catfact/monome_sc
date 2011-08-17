// scmonome_grid_test
//
// test application using the MonomeGridApp boilerplate class in a generic fashion.

~testApp = MonomeGridApp.new(
	name:"scmonome_grid_test",
	
	bounds:Rect(100, Window.screenBounds.height - 100, 260, 400),
	
	guiInitAction:{ arg app;
		// this .ctx variable is an Event-class object (see Event.html)
		// it is a potentially useful place to store your app's runtime variables
		// (including functions),
		// as an alternative to subclassing MonomeGridApp.
		app.ctx.toggleButton = Button(app.window, 100@20)
			.states_([
				["momentary", Color.black, Color.new(0.8, 0.9, 0.8)],
				["toggle", Color.black, Color.new(0.8, 0.8, 0.9)]])
			.action_({ arg but; app.ctx.toggle = (but.value > 0); });
			
		app.ctx.gridView = GridView(app.window, 250@250)
			.background_(Color.new(0.6, 0.6, 0.6))
			.squareColor_(Color.new(0.7, 0.7, 1.0))
			.gridColor_(Color.white)
			.gridInputAction_({arg grid; 
				grid.gridState.do({
					arg col, x;
					col.do({ 
						arg val, y;
						if (app.focus>0, { app.msg('/grid/led/set', [x, y, val]); });
					});
				});
			});
	},
	
	changeDeviceAction:{ arg app;
		app.ctx.gridView.clear;
	},
	
	monomeInitAction:{ arg app;
		app.ctx.toggle = false;
		app.ctx.what = 0;
		app.ctx.keyResponder = MonomeResponder.new(
			filter:(
				\id:{arg id; id.asSymbol == app.id.asSymbol},
				\cmd:'/grid/key'
			),
			function:{ arg r, e;
				//e.postln;
				if(app.ctx.toggle, {
					app.ctx.what = app.ctx.what + 1;
					app.ctx.what.postln;
					if (e.c > 0, {
						app.ctx.gridView.gridState[e.a][e.b] = 1 - app.ctx.gridView.gridState[e.a][e.b];
						{ app.ctx.gridView.refresh; }.defer;
					});
				}, {
					app.ctx.gridView.gridState[e.a][e.b] = e.c;
					{ app.ctx.gridView.refresh; }.defer;
				});
				app.msg('/grid/led/set', [e.a, e.b, app.ctx.gridView.gridState[e.a][e.b]]);
			}
		);
		
		MonomeClient.lostPortFunction = { arg id; if(id.asSymbol == app.id.asSymbol, { app.focus_(0); }); };
		MonomeClient.lostPrefixFunction = { arg prefix; postln("lost "++prefix); if(prefix.asSymbol == app.prefix.asSymbol, {
			app.id_(nil);
			app.focus_(0);
			app.prefix_("?");
		}); };
	},
	
	killAction:{ arg app;
		app.ctx.keyResponder.remove;
	}	
);