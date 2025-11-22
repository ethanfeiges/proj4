package edu.wisc.cs.sdn.vnet;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * TCPend - Reliable file transfer over UDP using Go-Back-N protocol
 * 
 * Sender mode: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>
 * Receiver mode: java TCPend -p <port> -m <mtu> -c <sws>
 */
public class TCPend {
    // Configuration parameters
    private int localPort;
    private String remoteIP;
    private int remotePort;
    private String filename;
    private int mtu;
    private int slidingWindowSize;
    private boolean isSender;
    
    // Network components
    private DatagramSocket socket;
    private InetAddress remoteAddress;
    
    // RTT estimation
    private static final double ALPHA = 0.875;
    private static final double BETA = 0.75;
    private static final long INITIAL_TIMEOUT_NS = 5_000_000_000L; // 5 seconds in nanoseconds
    private long estimatedRTT = INITIAL_TIMEOUT_NS;
    private long estimatedDEV = 0;
    private long timeout = INITIAL_TIMEOUT_NS;
    
    // Retransmission
    private static final int MAX_RETRANSMISSIONS = 16;
    private static final int FAST_RETRANSMIT_THRESHOLD = 3;
    
    // Sender state
    private int sendBase = 0;
    private int nextSeqNum = 0;
    private Map<Integer, PacketInfo> sentPackets = new ConcurrentHashMap<>();
    private Map<Integer, Integer> duplicateAckCount = new ConcurrentHashMap<>();
    
    // Receiver state
    private int expectedSeqNum = 0;
    private Map<Integer, byte[]> receivedBuffer = new TreeMap<>();
    private ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
    
    // Timer
    private ScheduledExecutorService timerExecutor = Executors.newScheduledThreadPool(1);
    private Map<Integer, ScheduledFuture<?>> retransmitTimers = new ConcurrentHashMap<>();
    
    /**
     * PacketInfo stores packet data and transmission information
     */
    private static class PacketInfo {
        TCPPacket packet;
        byte[] serializedData;
        long sendTime;
        int retransmitCount;
        
        PacketInfo(TCPPacket packet, byte[] data, long time) {
            this.packet = packet;
            this.serializedData = data;
            this.sendTime = time;
            this.retransmitCount = 0;
        }
    }
    
    public TCPend(int port, String remoteIP, int remotePort, String filename, int mtu, int sws) {
        this.localPort = port;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
        this.filename = filename;
        this.mtu = mtu;
        this.slidingWindowSize = sws;
        this.isSender = (remoteIP != null && filename != null);
    }
    
    /**
     * Start the TCP end system
     */
    public void start() throws Exception {
        socket = new DatagramSocket(localPort);
        System.out.println("TCPend started on port " + localPort);
        
        if (isSender) {
            if (remoteIP != null) {
                remoteAddress = InetAddress.getByName(remoteIP);
            }
            runSender();
        } else {
            runReceiver();
        }
    }
    
    /**
     * Sender mode: Send file to receiver
     */
    private void runSender() throws Exception {
        System.out.println("Running in SENDER mode");
        System.out.println("Remote: " + remoteIP + ":" + remotePort);
        System.out.println("File: " + filename);
        System.out.println("MTU: " + mtu + ", Window Size: " + slidingWindowSize);
        
        // Read file
        File file = new File(filename);
        byte[] fileData = Files.readAllBytes(file.toPath());
        System.out.println("File size: " + fileData.length + " bytes");
        
        // Calculate max data per packet
        int maxDataSize = mtu - TCPPacket.HEADER_SIZE;
        
        // Split file into chunks
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < fileData.length; i += maxDataSize) {
            int chunkSize = Math.min(maxDataSize, fileData.length - i);
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(fileData, i, chunk, 0, chunkSize);
            chunks.add(chunk);
        }
        
        System.out.println("Split into " + chunks.size() + " packets");
        
        // Start receiver thread
        Thread receiverThread = new Thread(this::receiverLoop);
        receiverThread.start();
        
        // Send packets using sliding window
        int totalBytesSent = 0;
        int packetIndex = 0;
        
        while (packetIndex < chunks.size() || !sentPackets.isEmpty()) {
            // Send new packets within window
            while (packetIndex < chunks.size() && 
                   (nextSeqNum - sendBase) < slidingWindowSize) {
                
                byte[] chunk = chunks.get(packetIndex);
                TCPPacket packet = new TCPPacket(
                    nextSeqNum,
                    0,
                    System.nanoTime(),
                    chunk.length,
                    0,
                    chunk
                );
                
                sendPacket(packet);
                nextSeqNum += chunk.length;
                packetIndex++;
            }
            
            // Wait for ACKs
            Thread.sleep(10);
        }
        
        System.out.println("File transfer complete. Total bytes: " + fileData.length);
        cleanup();
    }
    
    /**
     * Receiver mode: Receive file from sender
     */
    private void runReceiver() throws Exception {
        System.out.println("Running in RECEIVER mode");
        System.out.println("MTU: " + mtu + ", Window Size: " + slidingWindowSize);
        System.out.println("Waiting for data...");
        
        byte[] buffer = new byte[65535];
        
        while (true) {
            DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(udpPacket);
            
            // Remember sender address for first packet
            if (remoteAddress == null) {
                remoteAddress = udpPacket.getAddress();
                remotePort = udpPacket.getPort();
                System.out.println("Connected to sender: " + remoteAddress + ":" + remotePort);
            }
            
            // Deserialize TCP packet
            byte[] packetData = new byte[udpPacket.getLength()];
            System.arraycopy(udpPacket.getData(), 0, packetData, 0, udpPacket.getLength());
            
            TCPPacket packet = TCPPacket.deserialize(packetData);
            
            // Verify checksum
            if (!packet.verifyChecksum()) {
                System.err.println("Checksum failed for packet seq=" + packet.getSequenceNumber());
                continue;
            }
            
            handleReceivedPacket(packet);
        }
    }
    
    /**
     * Handle received packet at receiver
     */
    private void handleReceivedPacket(TCPPacket packet) throws IOException {
        int seqNum = packet.getSequenceNumber();
        byte[] data = packet.getData();
        
        System.out.println("Received: " + packet);
        
        if (seqNum == expectedSeqNum) {
            // In-order packet
            if (data.length > 0) {
                receivedData.write(data);
                expectedSeqNum += data.length;
                
                // Deliver any buffered in-order packets
                while (receivedBuffer.containsKey(expectedSeqNum)) {
                    byte[] bufferedData = receivedBuffer.remove(expectedSeqNum);
                    receivedData.write(bufferedData);
                    expectedSeqNum += bufferedData.length;
                }
            }
            
            // Send ACK for next expected byte
            sendAck(expectedSeqNum, packet.getTimestamp());
            
        } else if (seqNum > expectedSeqNum) {
            // Out-of-order packet - buffer it
            receivedBuffer.put(seqNum, data);
            
            // Send cumulative ACK (duplicate ACK)
            sendAck(expectedSeqNum, packet.getTimestamp());
            System.out.println("Out of order - buffered seq=" + seqNum + ", expecting=" + expectedSeqNum);
            
        } else {
            // Old packet - just ACK it
            sendAck(expectedSeqNum, packet.getTimestamp());
        }
    }
    
    /**
     * Send ACK packet
     */
    private void sendAck(int ackNum, long timestamp) throws IOException {
        TCPPacket ackPacket = new TCPPacket(
            0,
            ackNum,
            timestamp,
            0,
            TCPPacket.FLAG_ACK,
            null
        );
        
        byte[] packetData = ackPacket.serialize();
        DatagramPacket udpPacket = new DatagramPacket(
            packetData,
            packetData.length,
            remoteAddress,
            remotePort
        );
        
        socket.send(udpPacket);
        System.out.println("Sent ACK: " + ackNum);
    }
    
    /**
     * Send data packet
     */
    private void sendPacket(TCPPacket packet) throws IOException {
        byte[] packetData = packet.serialize();
        DatagramPacket udpPacket = new DatagramPacket(
            packetData,
            packetData.length,
            remoteAddress,
            remotePort
        );
        
        socket.send(udpPacket);
        
        // Store packet info
        PacketInfo info = new PacketInfo(packet, packetData, System.nanoTime());
        sentPackets.put(packet.getSequenceNumber(), info);
        
        // Schedule retransmit timer
        scheduleRetransmitTimer(packet.getSequenceNumber());
        
        System.out.println("Sent: " + packet);
    }
    
    /**
     * Receiver loop for ACKs (sender side)
     */
    private void receiverLoop() {
        byte[] buffer = new byte[65535];
        
        try {
            while (true) {
                DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(udpPacket);
                
                byte[] packetData = new byte[udpPacket.getLength()];
                System.arraycopy(udpPacket.getData(), 0, packetData, 0, udpPacket.getLength());
                
                TCPPacket ackPacket = TCPPacket.deserialize(packetData);
                
                if (!ackPacket.verifyChecksum()) {
                    System.err.println("ACK checksum failed");
                    continue;
                }
                
                handleAck(ackPacket);
            }
        } catch (Exception e) {
            // Socket closed
        }
    }
    
    /**
     * Handle received ACK (sender side)
     */
    private void handleAck(TCPPacket ackPacket) throws IOException {
        int ackNum = ackPacket.getAcknowledgment();
        long timestamp = ackPacket.getTimestamp();
        
        System.out.println("Received ACK: " + ackNum);
        
        // Update RTT if this ACK is for new data
        if (ackNum > sendBase) {
            long currentTime = System.nanoTime();
            long sampleRTT = currentTime - timestamp;
            
            if (sendBase == 0) {
                // First ACK
                estimatedRTT = sampleRTT;
                estimatedDEV = 0;
                timeout = 2 * estimatedRTT;
            } else {
                long sampleDEV = Math.abs(sampleRTT - estimatedRTT);
                estimatedRTT = (long)(ALPHA * estimatedRTT + (1 - ALPHA) * sampleRTT);
                estimatedDEV = (long)(BETA * estimatedDEV + (1 - BETA) * sampleDEV);
                timeout = estimatedRTT + 4 * estimatedDEV;
            }
            
            System.out.println("RTT updated - ERTT: " + (estimatedRTT/1000000) + "ms, Timeout: " + (timeout/1000000) + "ms");
            
            // Move window forward
            sendBase = ackNum;
            
            // Remove ACKed packets and cancel their timers
            Iterator<Map.Entry<Integer, PacketInfo>> it = sentPackets.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, PacketInfo> entry = it.next();
                int seqNum = entry.getKey();
                if (seqNum < ackNum) {
                    cancelRetransmitTimer(seqNum);
                    it.remove();
                }
            }
            
            // Clear duplicate ACK counter
            duplicateAckCount.clear();
            
        } else if (ackNum == sendBase) {
            // Duplicate ACK
            int dupCount = duplicateAckCount.getOrDefault(ackNum, 0) + 1;
            duplicateAckCount.put(ackNum, dupCount);
            
            if (dupCount == FAST_RETRANSMIT_THRESHOLD) {
                // Fast retransmit
                System.out.println("Fast retransmit triggered for seq=" + sendBase);
                retransmitPacket(sendBase);
            }
        }
    }
    
    /**
     * Schedule retransmit timer for a packet
     */
    private void scheduleRetransmitTimer(int seqNum) {
        ScheduledFuture<?> timer = timerExecutor.schedule(() -> {
            try {
                retransmitPacket(seqNum);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, timeout, TimeUnit.NANOSECONDS);
        
        retransmitTimers.put(seqNum, timer);
    }
    
    /**
     * Cancel retransmit timer for a packet
     */
    private void cancelRetransmitTimer(int seqNum) {
        ScheduledFuture<?> timer = retransmitTimers.remove(seqNum);
        if (timer != null) {
            timer.cancel(false);
        }
    }
    
    /**
     * Retransmit a packet
     */
    private void retransmitPacket(int seqNum) throws IOException {
        PacketInfo info = sentPackets.get(seqNum);
        if (info == null) {
            return; // Already ACKed
        }
        
        info.retransmitCount++;
        
        if (info.retransmitCount > MAX_RETRANSMISSIONS) {
            System.err.println("Max retransmissions exceeded for seq=" + seqNum);
            cleanup();
            System.exit(1);
        }
        
        System.out.println("Retransmitting seq=" + seqNum + " (attempt " + info.retransmitCount + ")");
        
        // Update timestamp
        info.packet.setTimestamp(System.nanoTime());
        info.sendTime = System.nanoTime();
        
        // Resend packet
        byte[] packetData = info.packet.serialize();
        DatagramPacket udpPacket = new DatagramPacket(
            packetData,
            packetData.length,
            remoteAddress,
            remotePort
        );
        socket.send(udpPacket);
        
        // Reschedule timer
        scheduleRetransmitTimer(seqNum);
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        timerExecutor.shutdown();
    }
    
    /**
     * Main entry point
     */
    public static void main(String[] args) {
        try {
            // Parse command line arguments
            int port = -1;
            String remoteIP = null;
            int remotePort = -1;
            String filename = null;
            int mtu = 1500;
            int sws = 10;
            
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-p":
                        port = Integer.parseInt(args[++i]);
                        break;
                    case "-s":
                        remoteIP = args[++i];
                        break;
                    case "-a":
                        remotePort = Integer.parseInt(args[++i]);
                        break;
                    case "-f":
                        filename = args[++i];
                        break;
                    case "-m":
                        mtu = Integer.parseInt(args[++i]);
                        break;
                    case "-c":
                        sws = Integer.parseInt(args[++i]);
                        break;
                    default:
                        printUsage();
                        System.exit(1);
                }
            }
            
            if (port == -1) {
                System.err.println("Error: -p <port> is required");
                printUsage();
                System.exit(1);
            }
            
            TCPend tcpEnd = new TCPend(port, remoteIP, remotePort, filename, mtu, sws);
            tcpEnd.start();
            
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  Sender mode: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");
        System.err.println("  Receiver mode: java TCPend -p <port> -m <mtu> -c <sws>");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  -p <port>        Local port number");
        System.err.println("  -s <remote IP>   Remote IP address (sender mode)");
        System.err.println("  -a <remote port> Remote port number (sender mode)");
        System.err.println("  -f <file name>   File to send (sender mode)");
        System.err.println("  -m <mtu>         Maximum transmission unit in bytes");
        System.err.println("  -c <sws>         Sliding window size (number of segments)");
    }
}
