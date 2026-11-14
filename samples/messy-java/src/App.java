package sample.shop;

public class App
{
    public static void main(String[]args)
    {
        Inventory inv=new Inventory();
        inv.receive("apple",12);
        inv.receive("pear",1);
        boolean ok=inv.reserve("apple",3);
        System.out.println("reserved: "+ok);
        for(String sku:new String[]{"apple","pear","plum"}){
            System.out.println(sku+" -> "+inv.levelOf(sku));
        }
        System.out.println("total="+inv.total());
    }
}
