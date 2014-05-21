package net.littlebigisland.droidibus.ibus;

import java.util.ArrayList;

public class BoardMonitorSystemCommand extends IBusSystemCommand {
	
	private byte BoardMonitorSystem = DeviceAddress.GraphicsNavigationDriver.toByte();
	private byte IKESystem = DeviceAddress.InstrumentClusterElectronics.toByte();
	
	// OBC Functions
	private byte OBCRequest = 0x41;
	private byte OBCRequestGet = 0x01;
	private byte OBCRequestReset = 0x02;

	public void mapReceived(ArrayList<Byte> msg) {
		// TODO Auto-generated method stub
		
	}
	
	public byte[] getFuel1(){
		return new byte[] {
				BoardMonitorSystem, 0x05, IKESystem, OBCRequest, 0x04, OBCRequestGet, (byte)0xFB
		};
	}
	
	public byte[] resetFuel1(){
		return new byte[] {
				BoardMonitorSystem, 0x05, IKESystem, OBCRequest, 0x04, OBCRequestReset, (byte)0xFB
		};
	}

}