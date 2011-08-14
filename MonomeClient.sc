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
	// dictionary class: \id->MonomeDevice
	// active connections:
	// tree class: \device->MonomeDevice, 
	classvar <responderCount;
		var addr = devices[id].serverAddr;
								devices[id].data[\savedPort] = msg[1];
							});
		// send port change request
		// ping to confirm 
				devices[id].stolen = true;
				doneAction.value(id, true);
				doneAction.value(id, false);
			});
		connections[id].postln;
		devices[id].data['savedPort'].postln;
		if(connections[id].notNil, {
			this.prRemoveRespondersAtDevice(id);
			devices[id].serverAddr.sendMsg('/sys/port', devices[id].data['savedPort']);
			devices[id].stolen = false;
		});
	}
	// same for all devices
		connections.do({ arg dat;
			this.prRemoveRespondersAtDevice(dat[\device].value.id);
			dat[\device].value.serverAddr.sendMsg('/sys/port', dat[\device].value.data['savedPort']);
			dat[\focus] = false;
		});
	// will attempt to send to an unconnected device
			dat[\device].value.serverAddr.sendBundle(
	
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
	}
		MonomeProtocol.deviceServerPatterns.do({
			arg pat;
			var sym;
			sym = (devices[id].data['/sys/prefix'][0].asString ++ pat.key).asSymbol;
		path.asAbsolutePath.postln;
		if  (File.exists(path.absolutePath), { 
			file = File.new(path.asAbsolutePath, "r");
			
			// TODO: remote device support
			devices[id].serverAddr.port = port;


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