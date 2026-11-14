package sample.tasks

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap



data class Task(val id:String,val title:String,val done:Boolean=false,val due:Instant?=null)

class TaskStore
{
    private val tasks=ConcurrentHashMap<String,Task>()

    fun add(title:String):Task{
        val id="t${tasks.size+1}"
        val task=Task(id,title)
        tasks[id]=task
        return task
    }

    fun complete(id:String):Boolean{
        val existing=tasks[id]?:return false
        tasks[id]=existing.copy(done=true)
        return true
    }



    fun overdue(now:Instant):List<Task>{
        return tasks.values
        .filter{!it.done}
        .filter{it.due!=null&&it.due<now}
        .sortedBy{it.due}
    }

    fun summary():String{
        val open=tasks.values.count{!it.done}
        val closed=tasks.size-open
        return "open=$open closed=$closed"
    }
}
