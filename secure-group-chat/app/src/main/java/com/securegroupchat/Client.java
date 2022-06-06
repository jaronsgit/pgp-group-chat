package com.securegroupchat;

import com.securegroupchat.PGPUtilities;

import java.net.*;
import java.io.*;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.util.logging.Logger;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.Enumeration;

public class Client {

    private final static Logger logger = Logger.getLogger(Client.class.getName());
    private final static String logTemplate = "%-15s%-5s%-10s%s";
    private final static String errTemplate = "%-15s%s";
    private final String hostname;
    private final int port;
    private final KeyPair personalKeyPair;
    private KeyStore keyRing;
    private String clientName;

    public Client() throws NoSuchAlgorithmException {
        this.hostname = "localhost";
        this.port = 4444;
        this.personalKeyPair = PGPUtilities.generateRSAKeyPair();
    }

    public Client(String hostname, int port) throws NoSuchAlgorithmException {
        this.hostname = hostname;
        this.port = port;
        this.personalKeyPair = PGPUtilities.generateRSAKeyPair();
    }

    public void addKeyToRing(String name, X509Certificate cert){
        try {
            keyRing.setCertificateEntry(name, cert);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void connectToServer() {
        try {
            Socket socket = new Socket(hostname, port);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            new IncomingHandler(socket, in, out).start();

        } catch (UnknownHostException e) {
            System.exit(1);
        } catch (IOException e) {
            System.exit(1);
        }
    }

    private void createKeyRing() throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
        this.keyRing = KeyStore.getInstance("PKCS12");
        this.keyRing.load(null,null);
    }

    public static void main(String[] args) throws NoSuchAlgorithmException {
        Client client;
        ConsoleHandler ch = new ConsoleHandler();

        boolean debug = false;
        if(args[args.length-1].equals("-debug")){
            ch.setLevel(Level.ALL);
            logger.addHandler(ch);
            logger.setLevel(Level.ALL);
            debug = true;
        }
        else{
            ch.setLevel(Level.INFO);
            logger.addHandler(ch);
            logger.setLevel(Level.INFO);
        }
        if ((args.length == 1 && debug) || (args.length == 0 && !debug)) {
            client = new Client();
            client.setup();
            client.connectToServer();
        } else if ((args.length == 3 && debug) || (args.length == 2 && !debug)) {
            String hostname = args[0];
            int port = Integer.parseInt(args[1]);
            client = new Client(hostname, port);
            client.setup();
            client.connectToServer();
        } else {
            logger.log(Level.INFO, String.format(errTemplate, "ARGS_ERR", "Usage: java Client <host name> <port number> [-debug]"));
            System.exit(1);
        }
    }

    private void setup(){
        String clientName = "";
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        try {
            System.out.println("Welcome to the Secure Group Chat Application.");
            System.out.print("Please enter your chat name: ");
            clientName = stdin.readLine();
            setClientName(clientName);
            System.out.println("Welcome, "+ clientName);

            //Get signed certificate
            X509Certificate certificate = new CertificateAuthority().generateSignedCertificate(clientName, personalKeyPair.getPublic());
            logger.log(Level.INFO, String.format(errTemplate, "[INIT]", "Certificate generated."));
            //System.out.println(certificate);

            createKeyRing();
            addKeyToRing(clientName,certificate); //Store client's certificate in in-memory KeyStore

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setClientName(String clientName){
        this.clientName = clientName;
    }

    private String getClientName(){
        return this.clientName;
    }

    private class IncomingHandler extends Thread {
        private Socket socket;
        private ObjectInputStream in;
        private ObjectOutputStream out;

        public IncomingHandler(Socket socket, ObjectInputStream in, ObjectOutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        // For concurrent writing to output stream
        private void writeToStream(Object obj) throws IOException{
            synchronized (this.out) {
                this.out.writeObject(obj);
            }
        }

        public void run() {
            while(true){
                try {

                    Object message = in.readObject();

                    if (message instanceof CommandMessage){

                        CommandMessage commandMessage = (CommandMessage) message;

                        // Successful Connection Message
                        if(commandMessage.getCommand().equals("CONN_SUCC")){
                            logger.log(Level.ALL, String.format(logTemplate, commandMessage.getSender(), "IN", "<CMD>", "CONN_SUCC"));
                            logger.log(Level.INFO, String.format(errTemplate, "[SERVER]", "Connected to server."));
                            //Send client's certificate to the server
                            CertificateMessage certificateMessage = new CertificateMessage(clientName,"<ALL>", (X509Certificate) keyRing.getCertificate(clientName), false);
                            writeToStream(certificateMessage);
                            logger.log(Level.ALL, String.format(logTemplate, certificateMessage.getReceiver(), "OUT", "<CERT>", "BROADCAST"));
                        }
                        // End Connection Message
                        else if(commandMessage.getCommand().equals("CONN_END")){
                            System.out.println("> Connection to server closed.");
                            break;
                        }else if(commandMessage.getCommand().equals("CERT_BROADCAST")){
                            logger.log(Level.ALL, String.format(logTemplate, commandMessage.getSender(), "IN", "<CMD>", "CERT_BROADCAST"));
                            logger.log(Level.INFO, String.format(errTemplate, "[SERVER]", "Certificate broadcast, you may now send messages."));
                            new OutgoingHandler(socket, in, out).start(); //Client ready to send messages
                        }

                    } else if (message instanceof CertificateMessage) {

                        CertificateMessage certificateMessage = (CertificateMessage) message;

                        // Handle CertificateMessages that are not from me
                        if(!certificateMessage.getSender().equals(Client.this.getClientName())){
                            X500Name x500name = new JcaX509CertificateHolder(certificateMessage.getCertificate()).getSubject();
                            String CNalias = x500name.toString().substring(3);

                            logger.log(Level.ALL, String.format(logTemplate, certificateMessage.getSender(), "IN", "<CERT>", CNalias));

                            try {
                                CertificateAuthority ca = new CertificateAuthority();
                                certificateMessage.getCertificate().verify(ca.getPublicKey()); // Verify certificate
                                Client.this.addKeyToRing(CNalias, certificateMessage.getCertificate());
                                logger.log(Level.ALL, String.format(errTemplate, "[CERT]", "Certificate verified: " + CNalias));
                                if(!certificateMessage.getReply()) {
                                    // Send client's certificate back as a reply
                                    CertificateMessage reply = new CertificateMessage(clientName, CNalias, (X509Certificate) keyRing.getCertificate(clientName), true);
                                    writeToStream(reply);
                                    logger.log(Level.ALL, String.format(logTemplate, certificateMessage.getReceiver(), "OUT", "<CERT>", CNalias));
                                }

                            } catch (Exception e) {
                                logger.log(Level.INFO, String.format(errTemplate, "[CERT]", "Could not verify certificate!"));
                            }
                        }

                    }else if (message instanceof  PGPMessage){
                        PGPMessage pgpMessage = (PGPMessage) message;
                        String sender = pgpMessage.getSender();
                        logger.log(Level.ALL, String.format(logTemplate, pgpMessage.getSender(), "IN", "<PGP>", pgpMessage.getReceiver()));

                        try {
                            byte[] decodedPGPdata = PGPUtilities.decode(pgpMessage.getPgpMessage(),personalKeyPair.getPrivate(),keyRing.getCertificate(sender).getPublicKey(), logger);
                            String plaintext = new String(decodedPGPdata);
                            System.out.println(sender+": "+plaintext);
                        } catch (InvalidAlgorithmParameterException | NoSuchPaddingException | IllegalBlockSizeException |
                            NoSuchAlgorithmException | BadPaddingException | SignatureException | InvalidKeyException e) {
                            logger.log(Level.INFO, String.format(errTemplate, "[DECODE]", "Could not decode PGP message!"));
                        }

                    }

                } catch (IOException | ClassNotFoundException | KeyStoreException | CertificateEncodingException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }

    private class OutgoingHandler extends Thread {
        private Socket socket;
        private ObjectOutputStream out;
        private ObjectInputStream in;

        public OutgoingHandler(Socket socket, ObjectInputStream in, ObjectOutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        private void writeToStream(Object obj) throws IOException{
            synchronized (this.out) {
                this.out.writeObject(obj);
            }
        } 

        public void run() {

            try (BufferedReader stdIn = new BufferedReader(new InputStreamReader(System.in))) {
                String userInput;
                do{
                    System.out.println("Enter message: ");
                    userInput = stdIn.readLine();

                    if(userInput.equals("quit")){
                        CommandMessage quit = new CommandMessage(Client.this.clientName, "server", "QUIT");
                        writeToStream(quit);
                        logger.log(Level.ALL, String.format(logTemplate, quit.getReceiver(), "OUT", "<CMD>", "QUIT"));
                    }
                    else{

                        Enumeration<String> enumeration = keyRing.aliases();

                        while(enumeration.hasMoreElements()) {
                            String alias = enumeration.nextElement();
                            if(!alias.equals(clientName)){
                                X509Certificate recipientCertificate = (X509Certificate) keyRing.getCertificate(alias);
                                byte[] encodedPGPdata = PGPUtilities.encode(userInput.getBytes(),personalKeyPair.getPrivate(),recipientCertificate.getPublicKey(), logger);
                                PGPMessage pgpMessage = new PGPMessage(clientName,alias,encodedPGPdata);
                                writeToStream(pgpMessage);
                                logger.log(Level.ALL, String.format(logTemplate, pgpMessage.getReceiver(), "OUT", "<PGP>", pgpMessage.getSender()));
                            }
                        }

                    }

                }while(!userInput.equals("quit"));
                
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            } catch (InvalidAlgorithmParameterException e) {
                throw new RuntimeException(e);
            } catch (NoSuchPaddingException e) {
                throw new RuntimeException(e);
            } catch (IllegalBlockSizeException e) {
                throw new RuntimeException(e);
            } catch (KeyStoreException e) {
                throw new RuntimeException(e);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            } catch (BadPaddingException e) {
                throw new RuntimeException(e);
            } catch (InvalidKeyException e) {
                throw new RuntimeException(e);
            }
        }
    }


}
