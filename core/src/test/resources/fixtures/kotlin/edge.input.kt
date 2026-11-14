package demo.edge

object Config{
    const val QUERY="""
        SELECT *
          FROM users
         WHERE tier = 'gold'
    """

    val greeting="hello, ${System.getProperty("user.name")?:"stranger"}!"

    var retries:Int?=null

    fun parse(raw:String?):Int{
        val n=raw ?. trim() ?. toIntOrNull() ?: -1
        return if(n>=0)n else 0
    }

    fun `weird name here`(){
        // nothing to do
        /* block /* nested */ comment */
    }
}

class Matrix(val cells:List<List<Double>>){
    operator fun get(r:Int,c:Int)=cells[r][c]
    fun sum()=cells.sumOf{row->row.sum()}
}
