package Nodes;
import command.ReserveCommand;
import command.SolveCommand;
import command.SolveHashIntervalCommand;
import command.StopHashBreakerCommand;
import hashBreaker.StringProvider;
import io.libp2p.core.*;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.discovery.MDnsDiscovery;
import io.libp2p.example.chat.Chat;
import io.libp2p.example.chat.ChatController;
import kotlin.Pair;
import subscriberAndPublisher.NodePublisher;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.*;

public class Node {

    public static Node instance;
    public static boolean isStarting;
    public Host host;
    public InetAddress address;
    public String addressString;
    public Discoverer discoverer;
    public List<PeerId> knownNodes = new ArrayList<>();
    public int solveBatchAmount = 3000000;
    public List<StringInterval> alreadyDone = new ArrayList<>();
    public List<String> currentWork = new ArrayList<>();
    public String hashToFind;
    public Boolean startNextInterval = true;
    public Boolean finished = true;
    public String currentStartString;
    public String currentEndString;
    public boolean conflict;
    StringInterval possibleInterval;

    Map<PeerId, StringInterval> recentReserves = new HashMap<>();

    Map<PeerId, List<StringInterval>> jobs = new HashMap<>();

    public static Node getInstance(){
        if(instance == null){
            instance = new Node();
        }
        return instance;
    }

    public Node(){
        try{
            this.address = getPrivateIp();
            this.addressString = this.address.toString().substring(1);

            this.host = new HostBuilder().protocol(new Chat(this::messageReceived)).listen("/ip4/"+this.addressString+"/tcp/0").build();
            this.host.start().get();
            this.discoverer = new MDnsDiscovery(this.host,"_ipfs-discovery._udp.local.",120,this.address);
            this.discoverer.getNewPeerFoundListeners().add((peer)->{
                this.peerFound(peer);
                return null;
            });
            this.discoverer.start();


            this.hashToFind = this.getHashFromString("bbbbbb");
            this.startHashBreaker();
        }
        catch (Exception e){
            System.out.println("FAILED TO CREATE");
        }
    }

    public String getHashFromString(String input){
        try{
            MessageDigest encoder = MessageDigest.getInstance("SHA-1");

            byte[] hashedBytes = encoder.digest(input.getBytes());

            StringBuilder hexStringBuilder = new StringBuilder();
            for (byte b : hashedBytes) {
                hexStringBuilder.append(String.format("%02x", b));
            }
            return hexStringBuilder.toString();
        }
        catch (Exception e){
            System.out.println(e);
        }
        return "";
    }

    public void callbackFromHashBreaker(String foundString){
        if(!foundString.equals("")){
            System.out.println("FOUND SOLUTION " +foundString + " " + this.getHashFromString(foundString) + " " + this.hashToFind);
            NodePublisher publisher = NodePublisher.getInstance();
            publisher.sendMessageToSubscribers("SOLVED:"+foundString);
            StopHashBreakerCommand command = new StopHashBreakerCommand();
            command.execute();

            this.cleanVariables();
        }
        else{
            StringInterval searchedInterval = new StringInterval(this.currentStartString,this.currentEndString);
            System.out.println("*** SEARCHED INTERVAL " + searchedInterval + " ***");
            if(this.currentStartString!=null){
                this.alreadyDone.add(searchedInterval);
                Collections.sort(this.alreadyDone);
                this.startNextInterval = true;
            }
        }
    }

    public void startHashBreaker(){
        this.finished = false;
        new Thread(()->{
            while(!this.finished && this.hashToFind!=null){
                try{
                    if(this.startNextInterval && !this.finished && this.hashToFind!=null){

                        this.reserveStringInterval();
                        this.conflict = false;

                        for(var x : this.recentReserves.keySet()){
                            this.checkIfReserveDoesNotCollide(this.recentReserves.get(x),x);
                        }
                        Thread.sleep(2000);

                        while(this.conflict){
                            this.conflict = false;
                            this.reserveStringInterval();
                            Thread.sleep(2000);
                        }
                        if(!this.finished && this.hashToFind!=null){
                            this.startNextInterval = false;
                            this.currentStartString = this.calcluateStartString();
                            this.currentEndString = this.calculateEndString(this.currentStartString);
                            SolveHashIntervalCommand command = new SolveHashIntervalCommand(this.currentStartString,this.currentEndString,this.hashToFind);
                            command.execute();
                        }

                    }
                    else{
                        Thread.sleep(3000);
                    }
                }
                catch (InterruptedException e){
                    System.out.println("INTERRUPTED");
                }

            }
            System.out.println("FINISHED");
        }).start();
    }

    public void reserveStringInterval(){
        String possibleStartString = this.calcluateStartString();
        StringInterval possibleInterval = new StringInterval(possibleStartString, this.calculateEndString(possibleStartString));
        ReserveCommand reserveCommand = new ReserveCommand(possibleInterval.startString, possibleInterval.endString);
        this.possibleInterval = possibleInterval;
        this.conflict=false;
        System.out.println("RESERVING " + this.possibleInterval);
        reserveCommand.execute();
    }

    public String calcluateStartString(){
        if(this.alreadyDone.isEmpty()){
            return "a";
        }
        System.out.println(this.alreadyDone);
        for(int i=0;i<this.alreadyDone.size()-1;i++){
            if(!StringProvider.generateNextString(this.alreadyDone.get(i).endString).equals(this.alreadyDone.get(i+1).startString) && !this.alreadyDone.get(i).equals(this.alreadyDone.get(i+1))){
                return StringProvider.generateNextString(this.alreadyDone.get(i).endString);
            }
        }
        String lastString = this.alreadyDone.getLast().endString;
        return StringProvider.generateNextString(lastString);
    }
    public String calculateEndString(String startString){
        return StringProvider.convertNumberToString(this.solveBatchAmount+StringProvider.convertStringToNumber(startString));
    }

    public Pair<Stream, ChatController> connectChat(PeerInfo info) {
        try {
            var chat = new Chat(this::messageReceived).dial(this.host,info.getPeerId(), info.getAddresses().get(0));
            return new Pair(chat.getStream().get(),chat.getController().get());
        }
        catch (Exception e){
            return null;
        }
    }

    public void checkIfReserveDoesNotCollide(StringInterval stringInterval, PeerId peerId){
        if(this.possibleInterval.equals(stringInterval)){
            if(this.host.getPeerId().toBase58().compareTo(peerId.toBase58())<0){
                NodePublisher nodePublisher = NodePublisher.getInstance();
                nodePublisher.sendMessageToSingleSubscriber("CONFLICT", peerId);

            }
            else{
                this.conflict  = true;
            }
        }
    }

    public void broadcastInterval(){
        NodePublisher nodePublisher = NodePublisher.getInstance();
        nodePublisher.sendMessageToSubscribers("SOLVING-"+this.currentStartString+":"+this.currentEndString);
    }

    public void broadcastPossibleInterval(String startString, String endString){
        NodePublisher nodePublisher = NodePublisher.getInstance();
        nodePublisher.sendMessageToSubscribers("RESERVE-"+startString+":"+endString);
    }

    public void send(String message) {
        NodePublisher p = NodePublisher.getInstance();
        p.sendMessageToSubscribers(message);
    }

    public void peerFound(PeerInfo info) {
        NodePublisher publisher = NodePublisher.getInstance();

        if (info.getPeerId().equals(this.host.getPeerId()) || publisher.peerAlreadyInSubscribed(info.getPeerId())) {
            return;
        }


        var chatConnection = connectChat(info);
        FriendNode friendNode = new FriendNode(info.getPeerId(), new Friend(info.getPeerId().toBase58(),chatConnection.getSecond()));
        publisher.addSubscriber(friendNode);
        if(!this.jobs.containsKey(info.getPeerId())){
            this.jobs.put(info.getPeerId(), new ArrayList<>());
        }

        if(this.hashToFind != null){
            publisher.sendMessageToSingleSubscriber("SOLVE THIS " + this.hashToFind, info.getPeerId());
            if(this.currentStartString!=null){
                this.broadcastInterval();
            }
        }

        chatConnection.getFirst().closeFuture().thenAccept((e)->{
            handleDisconnect(friendNode);
            if(!this.finished){
                StringInterval interval = new StringInterval("a","a");
                for(var x: this.alreadyDone){
                    if(this.jobs.get(info.getPeerId()).getLast().equals(x)){
                        interval = x;
                    }
                }
                this.alreadyDone.remove(interval);
                System.out.println("THEIR LAST JOB WAS " + this.jobs.get(info.getPeerId()).getLast());
            }
        });
    }
    public void handleDisconnect(FriendNode friendNode){
        NodePublisher publisher = NodePublisher.getInstance();
        publisher.removeSubscriber(friendNode);
    }

    public String messageReceived(PeerId id, String message){
        System.out.println("MESSAGE FROM " + id.toBase58() + ": " + message);

        if(message.startsWith("SOLVED")){
            StopHashBreakerCommand command = new StopHashBreakerCommand();
            command.execute();
            this.cleanVariables();
        }

        if(message.startsWith("SOLVE")){
            SolveCommand solveCommand = new SolveCommand(message);
            solveCommand.execute();
            synchronized (this){
                if(this.finished){
                    this.finished = false;
                    this.startHashBreaker();
                }
            }
        }
        if(message.startsWith("RESERVE")){
            String both = message.split("-")[1];
            String firstString = both.split(":")[0];
            String secondString = both.split(":")[1];
            StringInterval recievedInterval = new StringInterval(firstString,secondString);
            this.recentReserves.put(id, recievedInterval);
            this.checkIfReserveDoesNotCollide(recievedInterval, id);
        }
        if(message.startsWith("SOLVING")){
            String both = message.split("-")[1];
            String firstString = both.split(":")[0];
            String secondString = both.split(":")[1];


            StringInterval stringInterval = new StringInterval(firstString, secondString);
            if(firstString.equals("null")){
                var x = 123;
            }
            this.jobs.get(id).add(stringInterval);
            this.alreadyDone.add(stringInterval);
            Collections.sort(this.alreadyDone);
        }

        return "";
    }

    public InetAddress getPrivateIp() {
        try(final DatagramSocket socket = new DatagramSocket()){
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            String ip = socket.getLocalAddress().getHostAddress();
            System.out.println("THE IP IS: "+ip);
            return InetAddress.getByName(ip);
        }
        catch (Exception e){
            return null;
        }
    }
    public void cleanVariables(){
        this.finished = true;
        this.recentReserves = new HashMap<>();
        this.jobs = new HashMap<>();
        this.hashToFind = null;
        this.startNextInterval = true;
        this.currentStartString = null;
        this.currentEndString = null;
        this.conflict = false;
        this.possibleInterval = null;
        this.alreadyDone = new ArrayList<>();
        this.currentWork = new ArrayList<>();
        System.out.println("CLEANED NODE");
    }

}
