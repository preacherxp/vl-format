package sample.tasks

fun main(args:Array<String>){
    val store=TaskStore()
    when(args.firstOrNull()){
    "add"->{
    val title=args.drop(1).joinToString(" ")
    if(title.isBlank()){println("usage: add <title>");return}
    val t=store.add(title)
    println("added ${t.id}: ${t.title}")
    }
    "done"->{
    val ok=store.complete(args.getOrElse(1){""})
    println(if(ok)"completed"else"not found")
    }
    else->println(store.summary())
    }
}
