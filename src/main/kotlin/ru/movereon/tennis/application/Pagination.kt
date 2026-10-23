package ru.movereon.tennis.application

/** Clamp every SQL and in-memory list using the same page boundaries. */
internal fun pageIndex(requested:Int,total:Int,size:Int=8):Int {
    require(total>=0 && size>0)
    return requested.coerceIn(0,maxOf(0,(total-1)/size))
}

/** Copy only the visible range instead of allocating the rest of the list with drop(). */
internal fun <T> List<T>.page(requested:Int,size:Int=8):Page<T> {
    val index=pageIndex(requested,this.size,size)
    val offset=index*size
    return Page(subList(offset,offset+minOf(size,this.size-offset)).toList(),this.size,index,size)
}
