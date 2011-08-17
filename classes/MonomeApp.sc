// MonomeApp.sc

// classes to simplify monome application development using MonomeClient


// ----- MonomeGridApp
// boilerplate for the case of an application using a single grid device
// grid dimensions and position are arbitrary
// can enforce further limitations by subclassing
MonomeGridApp {
	// app variables
	var <name; 		// app's name, a String, use to store preferences or whaetever
	var <width, <height;	// size and position of targeted subgrid
	var <xOffset, <yOffset;
	var <id;				// id of the targeted grid device
	var <prefix;			// OSC prefix
	var <focus;			// flag indicating focus state
	var <>pingWait;		// wait time after ping
	var <>server;			// an scServer
	var <window;			// a window
	var <layout;			// a top-level FlowLayout for adding GUI widgets
	var <>deviceSettingsPath;	// path to store/recall device settings
	
	// these behavior flags are useful when dealing with multiple apps
	var <>restorePortWhenKilled = true;
	var <>restorePrefixWhenKilled = true;
	
	// a generic context variable.
	// this is to facilititate making arbitrary MonomeGridApps without subclassing. (see examples)
	// though, i still recommend subclassing when you can get around to it.
	var <>ctx;
	// there are several places where a user is likely to want custom actions.
	// again, subclassing is probably better in the long run.
	var <>killAction;			// function to evaulate when the app is asked to quit
	var <>resizeAction; 		// function to evaulate when resizing the grid
	var <>changeDeviceAction;	// function to evaulate after changing the target device
	
	// GUI variables
	var <connectionView;	// View containing connection widgets 
	var <connectionRows; 	// Array of row layout views
	var <deviceMenu, <focusBut, <prefixField;  // connection state widgets
	
	*new { arg name, server, prefix,
			width, height, xOffset, yOffset,
			audioInitAction, monomeInitAction, guiInitAction, 
			killAction, resizeAction, changeDeviceAction,
			bounds, background, buildGui=true;
			
		^super.new.init(name, server, prefix,
			width, height, xOffset, yOffset,
			audioInitAction, monomeInitAction, guiInitAction, 
			killAction, resizeAction, changeDeviceAction,
			bounds, background, buildGui
		);
	}
	
	init { arg n, s, pre, w, h, xoff, yoff, audioInitAction, monomeInitAction, guiInitAction, killAct, sizeAct, devAct, bounds, bg, gui;
		// assign app variables from arguments
		name = n;
		server = s;
		prefix = pre;
		
		if (prefix.isNil, { prefix = "/"++name.asString; });
		
		width = w;
		height = h;
		xOffset = xoff;
		yOffset = yoff;
		focus = 0;
		pingWait = 0.01;
		
		// assign actions
		killAction = killAct;
		resizeAction = sizeAct;
		changeDeviceAction = devAct;

		/// initialize the app context as an empty Event.
		ctx = Event.new;
		
		// init monome devices
		MonomeClient.scanDevices(doneAction:{
			// init gui
			if(gui, { {	
				// if you choose not to use this,
				// make sure to register the app with cmd-. or assign an .onClose 
				// so that the app gets killed and device focus released
				if(bounds.isNil, { bounds = Rect(80, Window.screenBounds.height - 100, 250, 110) });
				if(bg.isNil, { bg = Color.new(0.9, 0.9, 0.92); });
				window = Window(name, bounds);
				window.onClose_({ this.kill; });
				window.view.background_(bg);
				layout = window.addFlowLayout;
				// build connection control widgets
				this.buildConnectionView(window.view);
				window.front;
				guiInitAction.value(this);
			}.defer; });
			
			monomeInitAction.value(this);
		});
		
		// do audio initialization if we were given a server
		if(server.notNil, { server.waitForBoot{ audioInitAction.value(this); }; });
		
	}
	
	// create and populate a View and widgets for connection management
	buildConnectionView { arg parent, bounds;
		var colVal, fg, bg, bgStatic, bgBase, bgRed, bgGreen; // some Colors derived from the parent view's background

		colVal = parent.background.asHSV[2];		
		fg = parent.background.blend(if(colVal > 0.5, {Color.black}, {Color.white}), 0.9);
		bg = parent.background.blend(if(colVal > 0.5, {Color.white}, {Color.black}), 0.6);
		bgStatic = parent.background.blend(if(colVal > 0.5, {Color.white}, {Color.black}), 0.4);
		bgBase = parent.background.blend(if(colVal > 0.5, {Color.white}, {Color.black}), 0.2);
		bgRed = bg.blend(Color.red, 0.05);
		bgGreen = bg.blend(Color.green, 0.05);
		
		if(connectionView.isNil, {	// bail if you already made a connection view! sorry
			
			connectionView = VLayoutView(parent, 250@100).spacing_(4);
			connectionView.background_(bgBase);
			connectionRows = Array.fill(4, { HLayoutView(connectionView, connectionView.bounds.width@20).spacing_(3); });
			
			// label
			StaticText(connectionRows[0], 250@20)
				.background_(bgStatic)
				.stringColor_(fg)
				.align_(\center)
				.font_(Font("Monaco", 12))
				.string_("MONOME GRID DEVICE");
			// rescan button
			Button(connectionRows[1], 60@20)		
				.font_(Font("Monaco", 12))
				.states_([["RESCAN", fg, bg]])
				.canFocus_(false)
				.action_({ arg but; this.rescanDevices; });
			// store button
			Button(connectionRows[1], 60@20)
				.font_(Font("Monaco", 12))
				.states_([["STORE", fg, bgRed]])
				.canFocus_(false)
				.action_({ arg but; this.storeConnection; });
			// recall button
			Button(connectionRows[1], 60@20)
				.font_(Font("Monaco", 12))
				.states_([["RECALL", fg, bgGreen]])
				.canFocus_(false)
				.action_({ arg but; this.recallConnection; });
			// device menu
			deviceMenu = PopUpMenu(connectionRows[2], 160@20)
				.font_(Font("Monaco", 12))
				.background_(bg)
				.stringColor_(fg)
				.action_({ arg menu; this.id_(menu.item.asSymbol) });
			// focus button
			focusBut = Button(connectionRows[2], 80@20)
				.font_(Font("Monaco", 12))
				.states_([["unfocused", fg, bg], ["focused", fg, bgGreen]])
				.action_({ arg but; this.focus_(but.value) });
			// label
			StaticText(connectionRows[3], 60@20)
				.background_(bgStatic)
				.stringColor_(fg)
				.align_(\center)
				.font_(Font("Monaco", 12))
				.string_("prefix:");
			// prefix field
			prefixField = TextField(connectionRows[3], 160@20)
				.font_(Font("Monaco", 12))
				.background_(bg)
				.stringColor_(fg)
				.action_({ arg field; this.prefix_(field.value) });
			
			this.rescanDevices;
		}, {
			postln("MonomeGridApp: connection view already created.");
		});
	}
	
	rescanDevices {
		MonomeClient.scanDevices(doneAction:{
			defer {
				deviceMenu.postln;
				deviceMenu.items_(MonomeClient.getConnectedDevices.collect({ arg dev; dev.id.asSymbol }));
				if (id.notNil, {
					prefixField.string_(MonomeClient.devices[id].data['/sys/prefix'][0].asString);
				});
				connectionView.refresh;
			};
		});
	}
	
	prefix_ { arg str;
		if (str != prefix.asString, {
			if ((focus>0) && id.notNil, {
				MonomeClient.setDevicePrefix(id, str, doneAction:{
					arg id, ping;
					if (ping, {
					}, {
						prefix = str;
						postln("MonomeGridApp: device ping failed after attempted prefix change. id: "++id++", wait:"++pingWait);
					});
					
					{	prefixField.string_(prefix.asString);
						connectionView.refresh;
					}.defer;
				});
			});
		});
		^this
	}
	
	focus_ { arg v;
		if (v != focus, {
			if (v > 0, {
				if(id.notNil, {
					MonomeClient.connectDevice(id, wait:pingWait, doneAction: {
						arg id, ping;
						if(ping, {
							focus = v;
						}, {
							postln("MonomeGridApp: device ping failed after attempted connection. id: "++id++", wait:"++pingWait);
						});
						{ 	focusBut.value_(focus);
							prefixField.string_(MonomeClient.devices[id].data['/sys/prefix'][0].asString;);
							connectionView.refresh;
						}.defer;
					});

					/*
					MonomeClient.stealDevicePort(id, doneAction:{
						arg id, ping;
						if(ping, {
							focus = v;
						}, {
							postln("MonomeGridApp: device ping failed after attempted port change. id: "++id++", wait:"++pingWait);
						});
						{ 	focusBut.value_(focus);
//							prefixField.string_(MonomeClient.devices[id].data['/sys/prefix'][0].asString;);
							connectionView.refresh;
						}.defer;
					});
					if (prefix.notNil, {
						MonomeClient.setDevicePrefix(id, prefix, doneAction:{
							arg id, ping;
							if (ping, {
							}, {
								postln("MonomeGridApp: device ping failed after attempted prefix change. id: "++id++", wait:"++pingWait);
							});
							
							{	prefixField.string_(prefix.asString);
								connectionView.refresh;
							}.defer;
						});
					});
					*/
				});
			}, {
				if(id.notNil, {
					MonomeClient.disconnectDevice(id);
					
				//	MonomeClient.restoreDevicePort(id);
				//	MonomeClient.restoreDevicePort(id, doneAction:{
				//		arg id, ping;
				//		if(ping, {
							focus = v;
				//		}, {
				//			postln("MonomeGridApp: device ping failed after attempted port change. id: "++id++", wait:"++pingWait);
				//		});
						{ 	focusBut.value_(focus);
							connectionView.refresh;
						}.defer;
				//	});
				
				});
			});
		});
		^this
	}
	
	id_ { arg v;
		var size;
		if (v != id, {
			if(focus>0, {
				if(id.notNil, { MonomeClient.disconnectDevice(id); });
				id = v;
				MonomeClient.connectDevice(id, wait:pingWait, doneAction:{arg id, ping;
					if(ping.not, {
						postln("MonomeGridApp: device ping failed after attempted scan. id: "++id++", wait:"++pingWait);
						this.focus_(0);
					});
				});
			}, {
				id=v;
			});
			deviceMenu.value_(deviceMenu.items.indexOf(v.asSymbol));
			size = MonomeClient.devices[id].data['/sys/size'];
			if( (size[0].notNil) && (size[0] != width)
				|| (size[1].notNil) && (size[1] != height),
				{
					this.resize(size[0], size[1], xOffset, yOffset);
				}
			);
		});
		^this
	}
	
	width_ {arg v, doAction=true;
		if(width != v, {
			width = v;
			if(doAction, { resizeAction.value(this); });
		});
	}
	
	height_ {arg v, doAction=true;
		if(height != v, {
			height = v;
			if(doAction, { resizeAction.value(this); });
		});
	}
	
	xOffset_ {arg v, doAction=true;
		if(xOffset != v, {
			xOffset = v;
			if(doAction, { resizeAction.value(this); });
		});
	}
	
	yOffset_ {arg v, doAction=true;
		if(yOffset != v, {
			yOffset = v;
			if(doAction, { resizeAction.value(this); });
		});
	}
	
	resize { arg width=8, height=8, xOffset=0, yOffset=0;
		this.width_(width, false);
		this.height_(height, false);
		this.xOffset_(xOffset, false);
		this.yOffset_(yOffset, false);
		resizeAction.value(this);
	}
	
	
	// call this when you want the app to go away (e.g.,its window gets closed)
	kill {
		if(id.notNil && (focus > 0), {
			if (restorePortWhenKilled, {
				MonomeClient.restoreDevicePort(id);
			});
			if(prefix.notNil, {
				if (restorePrefixWhenKilled, {
					MonomeClient.restoreDevicePrefix(id);
				});
			});
		});
		killAction.value(this);
	}
	
	// convenience 
	msg { arg cmd, args;
		MonomeClient.msgDevice(id, cmd, args);
	}
	
	// store connection settings (including focus)
	storeConnection {	
		var path, file;
		if(id.notNil, {
			if(deviceSettingsPath.notNil, {
				path = deviceSettingsPath;
			}, {
				if (name.notNil, {
					path = name.asString ++ "_deviceSettings.dat";
				}, {
					path = "monomeGridApp_generic_deviceSettings.dat";
				});
			});
			file = File(path, "w");
			file.putString(id.asString ++ "\r");
			file.putString(focus.asString ++"\r");
			file.close;
		});
	}
	
	// recall connection setting (including focus)
	recallConnection {
		var file, path, iId, iFocus;		
		if(deviceSettingsPath.notNil, {
			path = deviceSettingsPath;
		}, {
			if (name.notNil, {
				path = name.asString ++ "_deviceSettings.dat";
			}, {
				path = "monomeGridApp_generic_deviceSettings.dat";
			});
		});
		if(File.exists(path), {
			file = File(path, "r");
			iId = file.getLine.asSymbol;
			iFocus = file.getLine.asInteger;
			file.close;
		});
		if(id.notNil && iFocus.notNil, {
			this.id_(iId);
			this.focus_(iFocus);
		}, {
			postln("MonomeGridApp: failed to parse settings file. path: "++path);
		});
	}

}


// a widget for displaying and editing grid data.
// TODO: cross platform
GridView : SCUserView {
	var <>cols, <>rows;
	var <>gridColor, <>squareColor;
	var <>gridThickness;
	var <>gridSize, <>squareSize;
	var <>gridState;
	var <>subView;
	var <>gridInputAction;
	var mouseX, mouseY;
	
	*viewClass { ^SCUserView } // this ensures that SCUserView's primitive is called


//	*new { arg parent, bounds, cols, rows;
//		^super.new.init(parent, bounds, cols, rows);
//	}

	init { arg inParent, inBounds, inCols, inRows;
		
		super.init(inParent, inBounds);
		this.drawFunc={ this.draw};
		
		gridColor=Color.new(0.2, 0.2, 0.2);
		squareColor=Color.new(0.9, 0.9, 0.9);
		
		cols = if(inCols.notNil, {inCols}, 8);
		rows = if(inRows.notNil, {inRows}, 8);
		
		gridThickness = 4;
		gridSize = (this.bounds.width - gridThickness) / cols;
		squareSize = gridSize - gridThickness;
		gridState = Array.fill(cols, { Array.fill(rows, {0}) });
	}

	draw{
		// fill with the grid color
		SCPen.fillColor = gridColor;
		Pen.addRect(Rect(0,0, this.bounds.width,this.bounds.height));
		Pen.fill;
		// draw the squares
		cols.do({ |x| rows.do({ |y|
			SCPen.fillColor = if(gridState[x][y] > 0, {squareColor}, {this.background});
			Pen.addRect(Rect(x * gridSize + gridThickness, y*gridSize + gridThickness, squareSize, squareSize));
			Pen.fill;
		})});	
	}

	mouseDown{ arg x, y, modifiers, buttonNumber, clickCount;
		([256, 0].includes(modifiers)).if{ // restrict to no modifier
			this.handleGridInput(x, y);
		};
		// this allows for user defined mouseDownAction
		mouseDownAction.value(this, x, y, modifiers, buttonNumber, clickCount);
	}

	mouseMove{ arg x, y, modifiers, buttonNumber, clickCount;
		var newVal;
		([256, 0].includes(modifiers)).if{ // restrict to no modifier
			this.handleGridInput(x, y);
		};
		// this allows for user defined mouseMoveActions
		mouseDownAction.value(this, x, y, modifiers, buttonNumber, clickCount);
	}
	
	handleGridInput { arg x, y;
		var nX, nY;
		nX = (x / this.bounds.width * cols).clip(0, cols-1).floor;
		nY = (y / this.bounds.width * rows).clip(0, rows - 1).floor;
		// [nX, nY].postln;
		if ((nX != mouseX) || (nY != mouseY), {
			mouseX = nX;
			mouseY = nY;
			gridState[nX][nY] = 1 - gridState[nX][nY];
			this.refresh;
		});
		gridInputAction.value(this);
	}
	
	clear {
		gridState.do({ arg col, x; col.do({arg val, y; gridState[x][y] = 0}); });
		this.refresh;
	}

}
