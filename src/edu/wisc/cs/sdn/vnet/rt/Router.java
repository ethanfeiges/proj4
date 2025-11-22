package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.MACAddress;
import java.nio.ByteBuffer;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }

	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}

		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}

		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		/********************************************************************/
		/* TODO: Handle packets                                             */

		switch(etherPacket.getEtherType())
		{
		case Ethernet.TYPE_IPv4:
			this.handleIpPacket(etherPacket, inIface);
			break;
		// Ignore all other packet types, for now
		}

		/********************************************************************/
	}

	private void handleIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		System.out.println("Handle IP packet");

		// Verify checksum
		short origCksum = ipPacket.getChecksum();
		ipPacket.resetChecksum();
		byte[] serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		short calcCksum = ipPacket.getChecksum();
		if (origCksum != calcCksum)
		{ return; }

		// Check TTL
		ipPacket.setTtl((byte)(ipPacket.getTtl()-1));
		if (0 == ipPacket.getTtl())
		{ 
			// Send ICMP Time Exceeded
			this.sendICMPMessage(etherPacket, inIface, (byte)11, (byte)0);
			return; 
		}

		// Reset checksum now that TTL is decremented
		ipPacket.resetChecksum();

		// Check if packet is destined for one of router's interfaces
		for (Iface iface : this.interfaces.values())
		{
			if (ipPacket.getDestinationAddress() == iface.getIpAddress())
			{ 
				// Handle packets destined for router
				byte protocol = ipPacket.getProtocol();
				if (protocol == IPv4.PROTOCOL_TCP || protocol == IPv4.PROTOCOL_UDP)
				{
					// Send ICMP Port Unreachable for TCP/UDP
					this.sendICMPMessage(etherPacket, inIface, (byte)3, (byte)3);
				}
				else if (protocol == IPv4.PROTOCOL_ICMP)
				{
					// Handle ICMP Echo Request
					ICMP icmpPacket = (ICMP)ipPacket.getPayload();
					if (icmpPacket.getIcmpType() == ICMP.TYPE_ECHO_REQUEST)
					{
						this.sendICMPEchoReply(etherPacket, inIface);
					}
				}
				return; 
			}
		}

		// Do route lookup and forward
		this.forwardIpPacket(etherPacket, inIface);
	}

	private void forwardIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
		System.out.println("Forward IP packet");

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		int dstAddr = ipPacket.getDestinationAddress();

		// Find matching route table entry 
		RouteEntry bestMatch = this.routeTable.lookup(dstAddr);

		// If no entry matched, send ICMP Destination Net Unreachable
		if (null == bestMatch)
		{ 
			this.sendICMPMessage(etherPacket, inIface, (byte)3, (byte)0);
			return; 
		}

		// Make sure we don't sent a packet back out the interface it came in
		Iface outIface = bestMatch.getInterface();
		if (outIface == inIface)
		{ return; }

		// Set source MAC address in Ethernet header
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());

		// If no gateway, then nextHop is IP destination
		int nextHop = bestMatch.getGatewayAddress();
		if (0 == nextHop)
		{ nextHop = dstAddr; }

		// Set destination MAC address in Ethernet header
		ArpEntry arpEntry = this.arpCache.lookup(nextHop);
		if (null == arpEntry)
		{ 
			// Send ICMP Destination Host Unreachable
			this.sendICMPMessage(etherPacket, inIface, (byte)3, (byte)1);
			return; 
		}
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());

		this.sendPacket(etherPacket, outIface);
	}

	/**
	 * Send an ICMP message in response to a received packet.
	 * @param etherPacket the original Ethernet packet
	 * @param inIface the interface on which the packet was received
	 * @param type ICMP type
	 * @param code ICMP code
	 */
	private void sendICMPMessage(Ethernet etherPacket, Iface inIface, byte type, byte code)
	{
		// Create Ethernet header
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(etherPacket.getSourceMACAddress());

		// Create IP header
		IPv4 ip = new IPv4();
		ip.setTtl((byte)64);
		ip.setProtocol(IPv4.PROTOCOL_ICMP);
		ip.setSourceAddress(inIface.getIpAddress());
		IPv4 origIp = (IPv4)etherPacket.getPayload();
		ip.setDestinationAddress(origIp.getSourceAddress());

		// Create ICMP header
		ICMP icmp = new ICMP();
		icmp.setIcmpType(type);
		icmp.setIcmpCode(code);

		// Create ICMP payload: 4 bytes padding + IP header + 8 bytes of original payload
		int origHeaderLen = origIp.getHeaderLength() * 4;
		byte[] origHeader = new byte[origHeaderLen];
		ByteBuffer bb = ByteBuffer.wrap(etherPacket.serialize());
		bb.position(etherPacket.getHeaderLength());
		bb.get(origHeader, 0, origHeaderLen);

		byte[] origPayload = origIp.getPayload().serialize();
		int payloadLen = Math.min(8, origPayload.length);

		byte[] icmpPayload = new byte[4 + origHeaderLen + payloadLen];
		ByteBuffer payloadBB = ByteBuffer.wrap(icmpPayload);
		payloadBB.putInt(0); // 4 bytes padding
		payloadBB.put(origHeader);
		payloadBB.put(origPayload, 0, payloadLen);

		Data data = new Data(icmpPayload);
		icmp.setPayload(data);

		// Assemble packet
		ip.setPayload(icmp);
		ether.setPayload(ip);

		// Send packet
		this.sendPacket(ether, inIface);
	}

	/**
	 * Send an ICMP Echo Reply in response to an Echo Request.
	 * @param etherPacket the original Ethernet packet containing the Echo Request
	 * @param inIface the interface on which the packet was received
	 */
	private void sendICMPEchoReply(Ethernet etherPacket, Iface inIface)
	{
		// Create Ethernet header
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(etherPacket.getSourceMACAddress());

		// Create IP header
		IPv4 origIp = (IPv4)etherPacket.getPayload();
		IPv4 ip = new IPv4();
		ip.setTtl((byte)64);
		ip.setProtocol(IPv4.PROTOCOL_ICMP);
		ip.setSourceAddress(origIp.getDestinationAddress());
		ip.setDestinationAddress(origIp.getSourceAddress());

		// Create ICMP header
		ICMP origIcmp = (ICMP)origIp.getPayload();
		ICMP icmp = new ICMP();
		icmp.setIcmpType((byte)0); // Echo Reply
		icmp.setIcmpCode((byte)0);
		icmp.setPayload(origIcmp.getPayload());

		// Assemble packet
		ip.setPayload(icmp);
		ether.setPayload(ip);

		// Send packet
		this.sendPacket(ether, inIface);
	}
}
