package edu.wisc.cs.sdn.vnet;

import java.nio.ByteBuffer;

/**
 * Custom TCP packet header format for reliable file transfer over UDP.
 * 
 * Packet format (24 bytes header + data):
 * - Sequence Number (4 bytes): Byte sequence number
 * - Acknowledgment (4 bytes): Next expected byte
 * - Timestamp (8 bytes): System.nanoTime() at transmission
 * - Length+Flags (4 bytes): 29 bits for length, 3 bits for S/A/F flags
 * - Checksum (2 bytes): One's complement checksum
 * - All Zeros (2 bytes): Reserved
 * - Data (variable): Payload data
 */
public class TCPPacket {
    // Header fields
    private int sequenceNumber;      // 4 bytes
    private int acknowledgment;      // 4 bytes
    private long timestamp;          // 8 bytes
    private int lengthAndFlags;      // 4 bytes (29 bits length + 3 bits flags)
    private short checksum;          // 2 bytes
    private short reserved;          // 2 bytes (all zeros)
    private byte[] data;             // Variable length
    
    // Flag bit positions
    public static final int FLAG_SYN = 0x04;  // S flag
    public static final int FLAG_ACK = 0x02;  // A flag
    public static final int FLAG_FIN = 0x01;  // F flag
    
    // Header size constant
    public static final int HEADER_SIZE = 24;
    
    // Maximum length value (29 bits)
    private static final int MAX_LENGTH = 0x1FFFFFFF;
    
    /**
     * Constructor for creating a new TCP packet
     */
    public TCPPacket(int seqNum, int ack, long ts, int length, int flags, byte[] payload) {
        this.sequenceNumber = seqNum;
        this.acknowledgment = ack;
        this.timestamp = ts;
        this.lengthAndFlags = ((length & MAX_LENGTH) << 3) | (flags & 0x07);
        this.reserved = 0;
        this.data = (payload != null) ? payload : new byte[0];
        this.checksum = 0;  // Will be calculated during serialization
    }
    
    /**
     * Default constructor
     */
    public TCPPacket() {
        this(0, 0, 0, 0, 0, null);
    }
    
    // Getters
    public int getSequenceNumber() { return sequenceNumber; }
    public int getAcknowledgment() { return acknowledgment; }
    public long getTimestamp() { return timestamp; }
    public int getLength() { return (lengthAndFlags >> 3) & MAX_LENGTH; }
    public int getFlags() { return lengthAndFlags & 0x07; }
    public boolean isSYN() { return (getFlags() & FLAG_SYN) != 0; }
    public boolean isACK() { return (getFlags() & FLAG_ACK) != 0; }
    public boolean isFIN() { return (getFlags() & FLAG_FIN) != 0; }
    public short getChecksum() { return checksum; }
    public byte[] getData() { return data; }
    
    // Setters
    public void setSequenceNumber(int seqNum) { this.sequenceNumber = seqNum; }
    public void setAcknowledgment(int ack) { this.acknowledgment = ack; }
    public void setTimestamp(long ts) { this.timestamp = ts; }
    public void setLength(int length) {
        this.lengthAndFlags = ((length & MAX_LENGTH) << 3) | (lengthAndFlags & 0x07);
    }
    public void setFlags(int flags) {
        this.lengthAndFlags = (lengthAndFlags & ~0x07) | (flags & 0x07);
    }
    public void setChecksum(short cs) { this.checksum = cs; }
    public void setData(byte[] payload) {
        this.data = (payload != null) ? payload : new byte[0];
    }
    
    /**
     * Serialize the packet to byte array
     */
    public byte[] serialize() {
        int totalLength = HEADER_SIZE + data.length;
        ByteBuffer bb = ByteBuffer.allocate(totalLength);
        
        // Write header fields
        bb.putInt(sequenceNumber);
        bb.putInt(acknowledgment);
        bb.putLong(timestamp);
        bb.putInt(lengthAndFlags);
        bb.putShort((short)0);  // Checksum placeholder
        bb.putShort(reserved);
        
        // Write data
        if (data.length > 0) {
            bb.put(data);
        }
        
        byte[] packetBytes = bb.array();
        
        // Calculate and set checksum
        short calculatedChecksum = calculateChecksum(packetBytes);
        bb.position(20);  // Position at checksum field
        bb.putShort(calculatedChecksum);
        
        return packetBytes;
    }
    
    /**
     * Deserialize packet from byte array
     */
    public static TCPPacket deserialize(byte[] packetData) {
        if (packetData.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Packet too short");
        }
        
        ByteBuffer bb = ByteBuffer.wrap(packetData);
        
        TCPPacket packet = new TCPPacket();
        packet.sequenceNumber = bb.getInt();
        packet.acknowledgment = bb.getInt();
        packet.timestamp = bb.getLong();
        packet.lengthAndFlags = bb.getInt();
        packet.checksum = bb.getShort();
        packet.reserved = bb.getShort();
        
        // Extract data
        int dataLength = packetData.length - HEADER_SIZE;
        if (dataLength > 0) {
            packet.data = new byte[dataLength];
            bb.get(packet.data);
        } else {
            packet.data = new byte[0];
        }
        
        return packet;
    }
    
    /**
     * Verify checksum of received packet
     */
    public boolean verifyChecksum() {
        byte[] packetBytes = new byte[HEADER_SIZE + data.length];
        ByteBuffer bb = ByteBuffer.wrap(packetBytes);
        
        bb.putInt(sequenceNumber);
        bb.putInt(acknowledgment);
        bb.putLong(timestamp);
        bb.putInt(lengthAndFlags);
        bb.putShort((short)0);  // Zero out checksum for calculation
        bb.putShort(reserved);
        if (data.length > 0) {
            bb.put(data);
        }
        
        short calculatedChecksum = calculateChecksum(packetBytes);
        return calculatedChecksum == checksum;
    }
    
    /**
     * Calculate one's complement checksum
     * As described in RFC 1071
     */
    private static short calculateChecksum(byte[] data) {
        int sum = 0;
        
        // Add all 16-bit words
        for (int i = 0; i < data.length - 1; i += 2) {
            int word = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += word;
            
            // Carry around
            if ((sum & 0xFFFF0000) != 0) {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
        }
        
        // Handle odd length
        if (data.length % 2 == 1) {
            sum += (data[data.length - 1] & 0xFF) << 8;
            if ((sum & 0xFFFF0000) != 0) {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
        }
        
        // One's complement
        return (short) ~sum;
    }
    
    @Override
    public String toString() {
        return String.format("TCPPacket[seq=%d, ack=%d, len=%d, flags=%s%s%s, ts=%d, checksum=0x%04x]",
            sequenceNumber, acknowledgment, getLength(),
            isSYN() ? "S" : "", isACK() ? "A" : "", isFIN() ? "F" : "",
            timestamp, checksum & 0xFFFF);
    }
}
