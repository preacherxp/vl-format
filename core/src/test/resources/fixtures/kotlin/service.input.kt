package demo.service

import java.time.Instant
import kotlin.math.max



class   UserService( private val repo:UserRepo,private val clock : Clock )
{
    private val cache = HashMap<String,User> ()

    fun findUser( id:String ):User?{
        val cached=cache[ id ]
        if(cached!=null){return cached}
        val user=repo.load(id)?:return null
        cache[id]=user
        return user
    }

    fun scoreOf(user:User):Int{
        val base=when(user.tier){
        "gold"->100
        "silver"->50
        else->10
        }
        val bonus=user.badges
        .filter{it.active}
        .sumOf{it.points*2}
        return max(base+bonus,0)
    }



    fun describe(user:User):String{
        return "user=${user.name} score=${scoreOf(user)}"
    }

    /**
       * Removes stale entries.
     */
    fun evict(olderThan:Instant){
        cache.entries.removeIf{( _,u )->u.loadedAt<olderThan}
    }
}
