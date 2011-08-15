// MonomeClient.sc

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

// FIXME: threadsafe pinging

//------ MonomeClient
// singleton class
// manages device list, handles incoming OSC, calls MonomeResponders
MonomeClient {
	// devices on file
	// dictionary class: \id->MonomeDevice
	classvar <devices;
	// active connections:
	// tree class: \device->MonomeDevice, 
	classvar <connections;
	// oneshot responders for pinging
	classvar <osr;
	classvar <devicePinged;
	// MonomeResponders
	classvar <>responders;
	// functions for dealing with port/prefix focus
	classvar <>lostPortFunction;
	classvar <>lostPrefixFunction;
	// runtime
	classvar <responderCount;
	classvar ping; // FIXME
	///// debug
	classvar <>debug = false;
	classvar <>dum = 0;
	
	*initClass {
		devices = Dictionary.new;
		connections = MultiLevelIdentityDictionary.new;
		osr = MultiLevelIdentityDictionary.new;
		devicePinged = MultiLevelIdentityDictionary.new;
		responders = List.new;
		responderCount = 0;
		ping = false;
		lostPortFunction = { arg id; postln("MonomeClient: lost port: "++id); };
		lostPrefixFunction = { arg id; postln("MonomeClient: lost prefix: "++id); };
	}
	
	// rebuild the device list (from .conf files)
	*scanDevices {
		var confs;
		devices.clear;
		confs = Platform.case(
			\osx, { PathName.new("~/Library/Preferences/org.monome.serialosc/").files },
			\linux, { PathName.new("~/.conf/serialosc/").files },
			\windows, { "TODO: windows .conf location".postln; nil } 
		);
		confs.do({ arg path; this.prScanConfFile(path) });
		if (debug) {devices.do({ |dev| dev.dump; }); }
	}
	
	// search for a particular device  (in .conf files)
	*scanDevice {arg id;   
		var ok = false;
		var path;
		
		devices.clear;
		path = PathName(Platform.case(
			\osx, { "~/Library/Preferences/org.monome.serialosc/" },
			\linux, { "~/.conf/serialosc/" },
			\windows, { "TODO: windows .conf location".postln; nil } 
		) ++ id ++ ".conf");
		
		^(this.prScanConfFile(path));
	}

	
	// ping a particular device, add it to connections list if successful
	// by default, this also steals the device's port
	*connectDevice { arg id, doneAction, wait=0.1, steal=true;
		var addr = devices[id].serverAddr;
		MonomeProtocol.systemServerStatusPatterns.do({
			arg pat;
//			devices[id].data.postln;
			devicePinged.put(id, pat.key, false);
			// oneshot responder
			osr.put(
				id,			// this device
				pat.key,		// osc from systemServer
				OSCresponderNode(
					addr,
					pat.key,
					{	arg t, r, msg;

						if ( (msg[0] == '/sys/port'), {
							if (devices[id].portStolen == false, {
								devices[id].data[\savedPort] = msg[1];
							});
						});
						
						if ( (msg[0] == '/sys/prefix'), {
							if (devices[id].prefixStolen == false, {
								devices[id].data[\savedPrefix] = msg[1];
							});
						});
						
						
						devicePinged.put(id, pat.key, true);
						
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
			var pingOk = true;
			wait.wait;
			MonomeProtocol.systemServerStatusPatterns.do({
				arg pat;
				pingOk = pingOk && (devicePinged[id][pat.key]);
			});
			if(pingOk, {
				connections.put(id, \device, `(devices[id]));
			});
			if(steal, {
				this.stealDevicePort(id);
			}, {
				this.restoreDevicePort(id);
			});
			doneAction.value(id, pingOk);		
		}.play;

	}
	
	// run connection routine for all devices on file
	// by default, this doesn't steal their port settings
	*connectAllDevices { arg doneAction, wait=0.05, steal=false;
		var pingCondition = Dictionary.new;
		devices.do({ arg dev; pingCondition[dev.id] = Condition.new(false); });
		devices.do({arg dev;
			this.connectDevice(dev.id, {
				pingCondition[dev.id].test = true;
				pingCondition[dev.id].signal;
			}, wait, steal);	
		});	
		Routine {
			pingCondition.do({ arg con; con.wait; });
			doneAction.value;
		}.play;
	}
	
	// destroy a device's responders, restore its port and prefix settings
	*disconnectDevice { arg id;
		if(connections[id].notNil, {
			// trash the OSC responders and other connection data
			this.prRemoveRespondersForDevice(id);
			connections.removeEmptyAt(id, \device);
			this.restoreDevicePort(id);
			this.restorePrefix(id);
			devices[id].portStolen = false;
			devices[id].portStolen = false;
		});
	}
	
	// same for all devices
	*disconnecAllDevices {
		var id;
		devices.do({ arg dev;
			id = dev.id;
			this.disconnectDevice(id);		
		});
	}
	
	// send port change request to device, thus stealing focus from other apps
	*stealDevicePort { arg id, doneAction, wait=0.05;
		var addr = devices[id].serverAddr;
		addr.sendMsg('/sys/port', NetAddr.langPort);
		ping = false;
		// ping to confirm 
		osr.put(
			id,
			'/sys/port',
			OSCresponderNode(
				addr,
				'/sys/port',
				{	arg t, r, msg;
					ping = (msg[1] == NetAddr.langPort);
				} 
			).add;
		);
		Routine {
			wait.wait;
			if(ping, {
				this.prAddRespondersAtDevice(id);
				devices[id].portStolen = true;
				doneAction.value(id, true);
			}, {
				doneAction.value(id, false);
			});
		}.play;
	}
	
	// reset a monome server's destination port
	*restoreDevicePort { arg id;
		devices[id].serverAddr.sendMsg('/sys/port', devices[id].data['savedPort']);
		devices[id].portStolen = false;

	}
	// convenience: all devices
	*restoreAllDevicePorts {
		connections.do({ arg dat;
			restoreDevicePort(dat.value.id);
		});
	}
	
	// reset a monome server's prefix
	*restoreDevicePrefix { arg id;
		devices[id].serverAddr.sendMsg('/sys/prefix', devices[id].data['savedPrefix']);
		devices[id].portStolen = false;
	}
	
	// convenience: all devices
	*restoreAllDevicePrefixes {
		connections.do({ arg dat;
			restoreDevicePrefix(dat.value.id);
		});
	}
	
	// send to device by ID
	// will attempt to send to an unconnected device
	*msgDevice { arg id, cmd ... args; 
		if (MonomeProtocol.systemClientPatterns.collect({|pat|pat.key}).matchItem(cmd), {
			devices[id].serverAddr.sendBundle(0.0, [cmd] ++ args);
		}, {
			devices[id].serverAddr.sendBundle(0.0, [devices[id].data['/sys/prefix'][0].asString ++ cmd] ++ args);
		});
	}
	
	// send to all connected devices
	*msgAllDevices { arg cmd ... args; 
		if (MonomeProtocol.systemClientPatterns.collect({|pat|pat.key}).matchItem(cmd), {
			connections.do({ arg dat;
				dat[\device].value.serverAddr.sendBundle(0.0, [dat[\device].value.data['/sys/prefix'][0] ++ cmd] ++ args);
			});
		}, {
			connections.do({ arg dat;
				dat[\device].value.serverAddr.sendBundle(0.0, [cmd] ++ args);
			});
		});
	}
	
	// store a reference to a MonomeResponder
	*addResponder { arg mr, priority, name;
		responders.add((\responder:`mr, \priority:priority, \name:name, \tag:responderCount)); 
		responders = responders.sortBy(\priority);
		mr.tag = responderCount;
		responderCount = responderCount + 1;
	}
	
	*getConnectedDevices {
		// FIXME: this returns ref's to MonomeDevices!
		// otherwise we get the data by copy and can't affect anything
		// this is a confusing workaround and there's got to be a better way...
	//	^(connections.collect({arg dat; dat[\device]}));
		^(connections.collect({arg dat; dat[\device].value}));
	}

	// ping a device, update its data, evaluate a doneAction on success
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
	}
	
	// set device prefix, evaluate a doneAction on success
	*setDevicePrefix {arg id, str, doneAction, wait=0.05;
		var addr = devices[id].serverAddr;
		// send port change request
		addr.sendMsg('/sys/prefix', str.asSymbol);
		ping = false;
		// ping to confirm 
		osr.put(
			id,
			'/sys/prefix',
			OSCresponderNode(
				addr,
				'/sys/prefix',
				{	arg t, r, msg;
					ping = (msg[1].asString == str);
				} 
			).add;
		);
		Routine {
			wait.wait;
			doneAction.value(id, ping);
			if(ping, {		
				devices[id].data['/sys/prefix'][0] = str.asSymbol;
				// irritatingly, OSCresponderNode (unlike OSCresponder)
				// cannot change its cmdName on the fly.
				// so (unless we want to steal all OSC traffic for monome commands),
				// we must destroy all this device's responderNodes, and make new ones... ugh
				this.prRemoveRespondersAtDevice(id);
				this.prAddRespondersAtDevice(id);
			});
		}.play;
	}
	
	///////////////////////////////////////////////////
	///-------------  private methods -----------------
	
	// build OSC responder list for device
	*prAddRespondersAtDevice { arg id;
		var addr;
		addr = devices[id].serverAddr;
		MonomeProtocol.deviceServerPatterns.do({
			arg pat;
			var sym;
			sym = (devices[id].data['/sys/prefix'][0].asString ++ pat.key).asSymbol;
			// postln([pat, id, sym, devices[id], devices[id].data]);
			connections.put(id, \responders, pat.key, 
				OSCresponderNode(addr, sym, {
					arg t, oscr, msg;
					var event, ate;
					// make an event, pass it to MonomeResponders,
					// break if it gets eaten (returning the MonomeResponder that ate it)
					block { |break| 
						ate = false;
						event = (	
							\time	: t,
							\id 		: id,
							\host	: devices[id].data['/sys/host'][0],
							\port 	: devices[id].serverAddr.port,
							\prefix	: devices[id].data['/sys/prefix'][0],
							\cmd		: pat.key,
							\a		: msg[1],
							\b		: msg[2],
							\c		: msg[3],
							\d		: msg[4]
						);
						// ("\n"++ event ++ "\n" ++ dum ++ "\n").postln; dum = dum + 1;
						responders.collect({|dat| dat.responder}).do({ arg mrRef;
							if (mrRef.value.active, {
								ate = mrRef.value.respond(event);
							});
							if(ate, { break.value(mrRef.value); });
						});
					} // block
				}).add;
			);

		});
		// add special responders for port and prefix changes
		connections.put(id, \responders, '/sys/port', 
			OSCresponderNode(addr, '/sys/port', {
				arg t, oscr, msg;
				if(devices[id].portStolen, {
					if(msg[1] != NetAddr.langPort, {
						devices[id].portStolen = false;
						lostPortFunction.value(id);
					});	
				});
			}).add;
		);
		connections.put(id, \responders, '/sys/prefix', 
			OSCresponderNode(addr, '/sys/prefix', {
				arg t, oscr, msg;
				if(devices[id].portStolen, {
					if(msg[1] != devices[id].data['/sys/prefix'][0], {
						devices[id].prefixStolen = false;
						this.prRemoveRespondersAtDevice(id);
						this.prAddRespondersAtDevice(id);
						lostPrefixFunction.value(id);
					});	
				});
			}).add;
		);
	}
	
	*prRemoveRespondersAtDevice { arg id;		
		MonomeProtocol.deviceServerPatterns.do({ arg pat;
			connections.removeEmptyAt(id, \responders, pat.key);
		});	
		connections.removeEmptyAt(id, \responders, '/sys/port');
		connections.removeEmptyAt(id, \responders, '/sys/prefix');
	}

	// process a .conf file
	*prScanConfFile { arg path;
		var file;
		path.asAbsolutePath.postln;
		if  (File.exists(path.absolutePath), { 
			var str, i, port, id, ok;
			file = File.new(path.asAbsolutePath, "r");
			ok = true;
			str = file.readAllString;
			/// scan for server port
			i = 0;
			i = str.find("server", true, i);
			ok = ok && i.notNil;
			i = str.find("port", true, i);
			ok = ok && i.notNil;
			i = str.find("=", true, i);
			ok = ok && i.notNil;
			port = str.copyRange(i+1, str.find("\n", true, i) - 1).asInteger;
			// id from the path
			id = path.fileNameWithoutExtension.asSymbol;
			// make a monome device data entry. 
			devices[id] = MonomeDevice.new;
			devices[id].data.add('/sys/id'-> [id]);
			// TODO: remote device support
			devices[id].serverAddr.port = port;
			file.close;
			^ok		// return false if search failed
		}, {
			^false		// return false if no file
		});
	}		
}


//------ MonomeDevice
MonomeDevice {
	// mirror latest values to/from serialosc
	var <>data;
	var <>serverAddr;
	var <>connected;
	var <>portStolen = false;
	var <>prefixStolen = false;
	
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
		if (MonomeProtocol.systemClientPatterns.collect({|pat|pat.key}).matchItem(cmd), {
			this.serverAddr.sendBundle(0.0, [cmd] ++ args);
		}, {
			this.serverAddr.sendBundle(0.0, [data['/sys/prefix'][0].asString ++ cmd] ++ args);
		});
	}
	
	clientAddr {
		^(NetAddr(data['/sys/host'][0],  data['/sys/port'][0]))
	}
	
	id {
		^(data['/sys/id'][0])
	}
}


//------s
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