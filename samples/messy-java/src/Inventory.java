package sample.shop;

import java.util.*;


public class Inventory
{
    private final Map<String,Integer> stock=new HashMap<>();

    public void receive( String sku,int qty ){
        if(qty<=0){throw new IllegalArgumentException("qty must be positive");}
        stock.merge(sku,qty,Integer::sum);
    }

    public boolean reserve(String sku,int qty){
        Integer available=stock.get(sku);
        if(available==null||available<qty){return false;}
        stock.put(sku,available-qty);
        return true;
    }

    public String levelOf(String sku){
        int n=stock.getOrDefault(sku,0);
        switch(n){
        case 0:
        return "out";
        case 1:
        case 2:
        return "low";
        default:
        return n>100?"overstocked":"ok";
        }
    }



    public int total(){
        int sum=0;
        for(int v:stock.values()){
            sum+=v;
        }
        return sum;
    }
}
