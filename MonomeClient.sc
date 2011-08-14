// MonomeClient.sc
// v.0.0

// sort-of analogous to MIDIClient / MIDIResponder
// however, MonomeClient and MonomeResponder are more agnostic concerning message type.
// these classes are just glue; user determines functional differentiation

// TODO:
// ideally, we would use zeroconf in a cleaner fashion.
// as far as i can tell this would require one of two things:
// either the SuperCollider lang source changes to scan for arbitrary bonjour/avahi service types
// or serialosc changes to use generic _osc._ service type
// for now, we use the rather nasty hack of scanning the monome configuration path for .conf files,
// extracting the port number for each device listed, and pinging them all...
//

// TODO:
// MonomeClient should create default responders for certain server strings:
// /sys/prefix, /sys/connect, /sys/disconnects

// TODO:
// cmd-.


// 


//------ MonomeClient
// singleton class
// manages device list, handles incoming OSC, calls MonomeResponders
MonomeClient {
	// devices on file
	// dictionary class: \id->MonomeDevice	classvar <devices;
	// active connections:
	// tree class: \device->MonomeDevice, 	classvar <connections;	// oneshot responders for pinging	classvar <osr;	// MonomeResponders	classvar <>responders;	// runtime
	classvar <responderCount;	classvar ping;	///// debug	classvar <>debug = false;	classvar <>dum = 0;		*initClass {		devices = Dictionary.new;		connections = MultiLevelIdentityDictionary.new;		osr = MultiLevelIdentityDictionary.new;		responders = List.new;		responderCount = 0;		ping = false;	}		// rebuild the device list (from .conf files)	*scanDevices {		var confs;		devices.clear;		confs = Platform.case(			\osx, { PathName.new("~/Library/Preferences/org.monome.serialosc/").files },			\linux, { PathName.new("~/.conf/serialosc/").files },			\windows, { "TODO: windows .conf location".postln; nil } 		);		confs.do({ arg path; this.prScanConfFile(path) });		if (debug) {devices.do({ |dev| dev.dump; }); }	}		// search for a particular device  (in .conf files)	*scanDevice {arg id;   		var ok = false;		var path;				devices.clear;		path = PathName(Platform.case(			\osx, { "~/Library/Preferences/org.monome.serialosc/" },			\linux, { "~/.conf/serialosc/" },			\windows, { "TODO: windows .conf location".postln; nil } 		) ++ id ++ ".conf");				^(this.prScanConfFile(path));	}		// ping a particular device, add it to connections list if successful	*connectDevice { arg id, doneAction, wait=0.1, steal=true;
		var addr = devices[id].serverAddr;		MonomeProtocol.systemServerStatusPatterns.do({			arg pat;			// oneshot responder			osr.put(				id,			// this device				pat.key,		// osc from systemServer				OSCresponderNode(					addr,					pat.key,					{	arg t, r, msg;						if ( (msg[0] == '/sys/port'), {							if (devices[id].stolen == false, {
								devices[id].data[\savedPort] = msg[1];
							});						});												devices[id].data[pat.key] = msg.copyRange(1, msg.size);						if(debug, { postln("got ping: "++ (id ++ msg)); });						osr[id][pat.key].remove;					} 				).add;			);		});				// send info request; each osr should get a ping shortly		addr.sendMsg('/sys/info', NetAddr.langPort);		// check on the results in the future		Routine {			var pingOk = true;			wait.wait;			MonomeProtocol.systemServerStatusPatterns.do({				arg pat;				pingOk = pingOk && (devices[id].data[pat.key].notNil);			});			devices[id].connected = pingOk;			if(steal, {				this.stealDevicePort(id);			}, {				this.restoreDevicePort(id);			});			doneAction.value(id, pingOk);		}.play;	}		// run connection routine for all devices on file	*connectAllDevices { arg doneAction, wait=0.05, steal=true;		var pingCondition = Dictionary.new;		devices.do({ arg dev; pingCondition[dev.id] = Condition.new(false); });		devices.do({arg dev;			this.connectDevice(dev.id, {				pingCondition[dev.id].test = true;				pingCondition[dev.id].signal;			}, wait, steal);			});			Routine {			pingCondition.do({ arg con; con.wait; });			doneAction.value;		}.play;	}			// send port change request to device, thus stealing focus from other apps	*stealDevicePort { arg id, doneAction, wait=0.05;		var addr = devices[id].serverAddr;
		// send port change request		addr.sendMsg('/sys/port', NetAddr.langPort);		ping = false;
		// ping to confirm 		osr.put(			id,			'/sys/port',			OSCresponderNode(				addr,				'/sys/port',				{	arg t, r, msg;					ping = (msg[1] == NetAddr.langPort);				} 			).add;		);		Routine {			wait.wait;			if(ping, {				this.prAddRespondersAtDevice(id);
				devices[id].stolen = true;
				doneAction.value(id, true);			}, {
				doneAction.value(id, false);
			});		}.play;	}		// kill OSC responders on a device connection, reset server's destination port	*restoreDevicePort { arg id;
		connections[id].postln;
		devices[id].data['savedPort'].postln;
		if(connections[id].notNil, {
			this.prRemoveRespondersAtDevice(id);
			devices[id].serverAddr.sendMsg('/sys/port', devices[id].data['savedPort']);
			devices[id].stolen = false;
		});
	}	
	// same for all devices	*restoreAllDevicePorts {
		connections.do({ arg dat;
			this.prRemoveRespondersAtDevice(dat[\device].value.id);
			dat[\device].value.serverAddr.sendMsg('/sys/port', dat[\device].value.data['savedPort']);
			dat[\focus] = false;
		});	}		// send to device by ID
	// will attempt to send to an unconnected device	*msgDevice { arg id, cmd ... args; 		devices[id].serverAddr.sendBundle(			0.0, [devices[id].data['/sys/prefix'][0] ++ cmd] ++ args		);	}		// send to all connected devices	*msgAllDevices { arg cmd ... args; 		connections.do({ arg dat;
			dat[\device].value.serverAddr.sendBundle(				0.0, [dat[\device].value.data['/sys/prefix'][0] ++ cmd] ++ args			);		});	}		// store a reference to a MonomeResponder	*addResponder { arg mr, priority, name;		responders.add((\responder:`mr, \priority:priority, \name:name, \tag:responderCount)); 		responders = responders.sortBy(\priority);		mr.tag = responderCount;		responderCount = responderCount + 1;	}
	
	*getConnectedDevices {
		^(connections.collect({arg dat; dat[\device].value}));
	}

	// just ping the device and refresh 
	*refreshDeviceInfo { arg id, wait=0.01, doneAction;
		var addr = devices[id].serverAddr;
		var dat = Event.new;
		MonomeProtocol.systemServerStatusPatterns.do({
			arg pat;
			// oneshot responder
			osr.put(
				id,			// this device
				pat.key,		// osc from systemServer
				OSCresponderNode(
					addr,
					pat.key,
					{	arg t, r, msg;
						devices[id].data[pat.key] = msg.copyRange(1, msg.size);
						if(debug, { postln("got ping: "++ (id ++ msg)); });
						osr[id][pat.key].remove;
					} 
				).add;
			);
		});
		// send info request; each osr should get a ping shortly
		addr.sendMsg('/sys/info', NetAddr.langPort);
		// check on the results in the future
		Routine {
			wait.wait;
			MonomeProtocol.systemServerStatusPatterns.do({
				arg pat;
				dat[pat.key] = devices[id].data[pat.key];
			});

			doneAction.value(id, dat);
		}.play;
	}			///////////////////////////////////////////////////	///-------------  private methods -----------------		// build OSC responder list for device	*prAddRespondersAtDevice { arg id;		var addr;		connections.put(id, \device, `(devices[id]));	// store a reference to the device data		connections.put(id, \focus, false);			// has SC stolen focus with a port request		addr = devices[id].serverAddr;
		MonomeProtocol.deviceServerPatterns.do({
			arg pat;
			var sym;
			sym = (devices[id].data['/sys/prefix'][0].asString ++ pat.key).asSymbol;			// postln([pat, id, sym, devices[id], devices[id].data]);			connections.put(id, \responders, pat.key, 				OSCresponderNode(addr, sym, {					arg t, oscr, msg;					var event, ate;					// make an event, pass it to MonomeResponders,					// break if it gets eaten (returning the MonomeResponder that ate it)					block { |break| 						ate = false;						event = (								\time	: t,							\id 		: id,							\host	: devices[id].data['/sys/host'][0],							\port 	: devices[id].serverAddr.port,							\prefix	: devices[id].data['/sys/prefix'][0],							\cmd		: pat.key,							\a		: msg[1],							\b		: msg[2],							\c		: msg[3],							\d		: msg[4]						);						// ("\n"++ event ++ "\n" ++ dum ++ "\n").postln; dum = dum + 1;						responders.collect({|dat| dat.responder}).do({ arg mrRef;							if (mrRef.value.active, {								ate = mrRef.value.respond(event);							});							if(ate, { break.value(mrRef.value); });						});					} // block				}).add;			);		});	}		// trash OSC responder list for device	*prRemoveRespondersAtDevice { arg id;		MonomeProtocol.deviceServerPatterns.do({ arg pat;			connections.removeEmptyAt(id, \responders, pat.key);		});			connections.removeEmptyAt(id, \device);	}		// process a .conf file	*prScanConfFile { arg path;		var file;
		path.asAbsolutePath.postln;
		if  (File.exists(path.absolutePath), { 			var str, i, port, id, ok;
			file = File.new(path.asAbsolutePath, "r");			ok = true;			str = file.readAllString;			/// scan for server port			i = 0;			i = str.find("server", true, i);			ok = ok && i.notNil;			i = str.find("port", true, i);			ok = ok && i.notNil;			i = str.find("=", true, i);			ok = ok && i.notNil;			port = str.copyRange(i+1, str.find("\n", true, i) - 1).asInteger;
						// id from the path			id = path.fileNameWithoutExtension.asSymbol;			// make a monome device data entry. 			devices[id] = MonomeDevice.new;			devices[id].data.add('/sys/id'-> [id]);
			// TODO: remote device support
			devices[id].serverAddr.port = port;			file.close;			^ok		// return false if search failed		}, {			^false		// return false if no file		});	}		}


//------ MonomeDevice
MonomeDevice {
	// mirror latest values to/from serialosc
	var <>data;
	var <>serverAddr;
	var <>connected;
	var <>portStolen = false;
	
	*new {
		^super.new.init;
	}
	
	init {
		data = Dictionary.new;
		connected = false;
		serverAddr = NetAddr("127.0.0.1", nil);
		data['/sys/host'] = ["127.0.0.1"];
	}
	
	msg { arg cmd ... args;
		data.put(cmd, args);
		this.serverAddr.sendBundle(0.0, [data['/sys/prefix'][0].asString ++ cmd] ++ args);
	}
	
	clientAddr {
		^(NetAddr(data['/sys/host'][0],  data['/sys/port'][0]))
	}
	
}


//------MonomeEvent
// helper
MonomeEvent {
	classvar <params;	
	*initClass {
		params = [
			\time,
			\id,
			\port,
			\cmd,
			\a,
			\b,
			\c,
			\d
		];
	}
}

// ----- MonomeResponder
// logic class, process events
MonomeResponder {
	var <>active;
	var <priority;
	var <name;
	var <>swallow;
	var <>function;
	var <>filter;
	var <>tag;
	
	// create it with an Event describing your targets
	*new { arg function, filter, priority, name;
		^super.new.init(function, filter, priority, name);
	}
	
	init { arg iFunction, iFilter, iPriority, iName;
		if (iFilter.notNil, {
			filter = iFilter;
		}, {
			filter = Event.new;
			MonomeEvent.params.do({ arg sym;
				filter[sym] = nil;
			});
		});

		priority = if(iPriority.notNil, {iPriority}, {0});
		function = iFunction;
		name = iName;
		
		active = true;
		swallow = false;
		MonomeClient.addResponder(this, priority, name);
	}
	
	respond { arg e;
		var ok = true;
		MonomeEvent.params.do({ arg sym;
			if (filter.at(sym).notNil && e.at(sym).notNil, {
				ok = ok && ((filter.at(sym)).matchItem(e.at(sym)));
			});
		});
		if (ok, {
			function.value(this, e);
			^swallow
		}, {
			^false;
		});
	}
	
	setPriority { arg pr;
		var idx;
		priority = pr;
		MonomeClient.responders.do({ arg dat, i;
			if(tag.asInteger == dat.tag.asInteger, {
				idx = i;
			});
		});
		if(idx.notNil, {
			MonomeClient.responders[idx].put(\priority, pr);
			MonomeClient.responders = MonomeClient.responders.sortBy(\priority);
		});
	}
	
	remove {
		MonomeClient.responders = MonomeClient.responders.reject({ |dat| dat.tag == tag })
			.sortBy(\priority);
		this.free;
	}
}