
// -------- MonomeProtocol
// singleton class, serves up OSC patterns
MonomeProtocol {
	classvar <systemServerStatusPatterns;
	classvar <systemServerCommandPatterns;
	classvar <systemClientPatterns;
	classvar <deviceServerPatterns;
	classvar <deviceClientPatterns;
	
	*initClass {
		systemServerStatusPatterns = [
			'/sys/port' 		-> [\port],
			'/sys/id' 		-> [\id],
			'/sys/size' 		-> [\x, \y],
			'/sys/host' 		-> [\address],
			'/sys/prefix' 	-> [\string],
			'/sys/rotation' 	-> [\degrees]
		];
		
		systemServerCommandPatterns = [
			'/sys/disconnect'	-> [],
			'/sys/connect' 	-> []
		];
		
		systemClientPatterns = [
			'/sys/port' 		-> [\port],
			'/sys/host' 		-> [\host],
			'/sys/prefix' 	-> [\prefix],
			'/sys/rotation' 	-> [\degrees],
			'/sys/info'		-> [\port_opt, \host_opt]
		];
		
		deviceServerPatterns = [
			'/grid/key'	-> [\x, \y, \s],
			'/tilt'		-> [\n, \x, \y, \z],
			'/enc/delta'	-> [\n, \d],
			'/enc/key'	-> [\n, \s]
		];
		
		deviceClientPatterns = [
			'/grid/led/set' -> [\x, \y, \s],
			'/grid/led/all' -> [\s],
			'/grid/led/map' -> [ \x_offset, \y_offset, \s0, \s1, \s2, \s3, \s4, \s5, \s6, \s7],
			'/grid/led/row' -> [ \x_offset, \y, \s0, \s1_opt, \s2_opt, \s3_opt],
			'/grid/led/col' -> [ \x, \y_offset, \s0, \s1_opt, \s2_opt, \s3_opt],
			'/grid/led/intensity' -> [\l],
			'/grid/led/level/set' -> [\x, \y, \l],
			'/grid/led/level/all' -> [\l],
			'/grid/led/level/map' -> [\x_off, \y_off,
				\l0, \l1, \l2, \l3, \l4, \l5, \l6, \l7,
				\l8, \l9, \l10, \l11, \l12, \l13, \l14, \l15,
				\l16, \l17, \l18, \l19, \l20, \l21, \l22, \l23,
				\l24, \l25, \l26, \l27, \l28, \l29, \l30, \l31,
				\l32, \l33, \l34, \l35, \l36, \l37, \l38, \l39,
				\l40, \l41, \l42, \l43, \l44, \l45, \l46, \l47,
				\l48, \l49, \l50, \l51, \l52, \l53, \l54, \l55,
				\l56, \l57, \l58, \l59, \l60, \l61, \l62, \l63 ],
			'/grid/led/level/row' -> [\x_off, \y, \l0, \l1_opt, \l2_opt, \l3_opt],
			'/grid/led/level/col' -> [\x, \y_off, \l0, \l1_opt, \l2_opt, \l3_opt],
			'/tilt/set' -> [\n, \s],
			'/ring/set' -> [\x, \n, \l],
			'/ring/all' -> [\n, \l],
			'/ring/map' -> [\n,
				\l0, \l1, \l2, \l3, \l4, \l5, \l6, \l7,
				\l8, \l9, \l10, \l11, \l12, \l13, \l14, \l15,
				\l16, \l17, \l18, \l19, \l20, \l21, \l22, \l23,
				\l24, \l25, \l26, \l27, \l28, \l29, \l30, \l31,
				\l32, \l33, \l34, \l35, \l36, \l37, \l38, \l39,
				\l40, \l41, \l42, \l43, \l44, \l45, \l46, \l47,
				\l48, \l49, \l50, \l51, \l52, \l53, \l54, \l55,
				\l56, \l57, \l58, \l59, \l60, \l61, \l62, \l63 ],
			'/ring/range' -> [\n, \x1, \x2, \l ]
		];
	}
	
	*getServerPatterns {
		^(systemServerStatusPatterns ++ systemServerCommandPatterns ++ deviceServerPatterns)
	}
	
	*getClientPatterns {
		^(systemClientPatterns ++ deviceClientPatterns)
	}
}