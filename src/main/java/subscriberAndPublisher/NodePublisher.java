package subscriberAndPublisher;
import node.Node;
import io.libp2p.core.PeerId;

import java.util.ArrayList;
import java.util.List;

public class NodePublisher implements Publisher{

    public static NodePublisher intance;
    List<FriendNode> subscribers = new ArrayList<>();

    public static synchronized NodePublisher getInstance(){
        if(intance==null){
            intance = new NodePublisher();
        }
        return intance;
    }

    public boolean peerAlreadyInSubscribed(PeerId peerId){
        return this.getByPeerId(peerId) != null;
    }

    public void sendMessageToSingleSubscriber(String message, PeerId peerId){
        FriendNode friendNode = getByPeerId(peerId);
        if(friendNode == null){
            if(Node.showOutput) {
                System.out.println("COULD NOT FIND NODE");
            }
            return;
        }

        friendNode.sendMessage(message);
    }

    public FriendNode getByPeerId(PeerId peerId){
        for(var node: subscribers){
            if(node.peerId.toBase58().equals(peerId.toBase58())){
                return node;
            }
        }
        return null;
    }

    @Override
    public void sendMessageToSubscribers(String message) {
        for(var subscriber: this.subscribers){
            subscriber.sendMessage(message);
        }
    }

    @Override
    public synchronized void addSubscriber(Subscriber friendNode) {
        var fNode = (FriendNode) friendNode;
        if(this.subscribers.stream().anyMatch(node -> node.peerId.toBase58().equals(fNode.peerId.toBase58()))){
            return;
        }
        if(Node.showOutput) {
            System.out.println("NODE " + fNode.peerId + " HAS CONNECTED");
        }
        this.subscribers.add(fNode);
    }

    @Override
    public synchronized void removeSubscriber(Subscriber friendNode) {
        var fNode = (FriendNode) friendNode;
        if(this.subscribers.stream().noneMatch(node -> node.peerId.toBase58().equals(fNode.peerId.toBase58()))){
            return;
        }
        if(Node.showOutput) {
            System.out.println("NODE " + fNode.peerId + " HAS DISCONNECTED");
        }
        this.subscribers.remove(friendNode);
    }

}
