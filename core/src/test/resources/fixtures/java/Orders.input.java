package demo.orders;

import java.util.*;


public class Orders
{
    private final Map<String,List<Order>> byCustomer=new HashMap<>();

    public void add( Order o ){
        byCustomer.computeIfAbsent(o.customerId(),k->new ArrayList<>()).add(o);
    }

    public int totalFor(String customerId){
        List<Order> orders=byCustomer.get(customerId);
        if(orders==null){return 0;}
        int total=0;
        for(Order o:orders){
            total+=o.amount();
        }
        return total;
    }

    public String label(int total){
        switch(total/100){
        case 0:
        return "small";
        case 1:
        case 2:
        return "medium";
        default:
        return total>1000?"huge":"large";
        }
    }

    public long countLarge(){
        return byCustomer.values().stream()
        .flatMap(List::stream)
        .filter(o->o.amount()>=500)
        .count();
    }
}
