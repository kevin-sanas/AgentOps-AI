package dev.agentops.targets;

import dev.agentops.shared.ApiError;
import java.net.InetAddress;
import java.util.Arrays;

/** Numeric literals only: parsing a policy must never cause a DNS lookup. */
public record Cidr(byte[] network,int prefix) {
    public Cidr { network=network.clone(); }
    @Override public byte[] network(){return network.clone();}
    public static Cidr parse(String input){
        try{
            String[] parts=input.split("/",-1);if(parts.length!=2||!parts[1].matches("[0-9]{1,3}"))throw new IllegalArgumentException();
            String literal=parts[0];byte[] bytes;
            if(literal.contains(":")){
                if(!literal.matches("[a-fA-F0-9:]+"))throw new IllegalArgumentException();
                bytes=InetAddress.getByName(literal).getAddress();if(bytes.length!=16)throw new IllegalArgumentException();
            }else{
                String[] octets=literal.split("\\.",-1);if(octets.length!=4)throw new IllegalArgumentException();bytes=new byte[4];
                for(int i=0;i<4;i++){if(!octets[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException();int n=Integer.parseInt(octets[i]);if(n>255)throw new IllegalArgumentException();bytes[i]=(byte)n;}
            }
            int prefix=Integer.parseInt(parts[1]);if(prefix<0||prefix>bytes.length*8)throw new IllegalArgumentException();
            for(int bit=prefix;bit<bytes.length*8;bit++)bytes[bit/8]&=(byte)~(1<<(7-bit%8));
            return new Cidr(bytes,prefix);
        }catch(Exception e){throw ApiError.badRequest("INVALID_PRIVATE_CIDR");}
    }
    public boolean contains(InetAddress address){return contains(address.getAddress());}
    public boolean contains(Cidr other){return prefix<=other.prefix && contains(other.network);}
    private boolean contains(byte[] value){
        if(value.length!=network.length)return false;
        for(int bit=0;bit<prefix;bit++)if((value[bit/8]&(1<<(7-bit%8)))!=(network[bit/8]&(1<<(7-bit%8))))return false;
        return true;
    }
    public boolean isPrivate(){
        if(network.length==4){int a=network[0]&255,b=network[1]&255;return a==10&&prefix>=8||a==172&&b>=16&&b<=31&&prefix>=12||a==192&&b==168&&prefix>=16;}
        return (network[0]&254)==252&&prefix>=7;
    }
    @Override public String toString(){try{return InetAddress.getByAddress(network).getHostAddress()+"/"+prefix;}catch(Exception e){throw new IllegalStateException();}}
}
