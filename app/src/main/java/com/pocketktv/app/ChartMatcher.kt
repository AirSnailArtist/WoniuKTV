package com.pocketktv.app

import java.text.Normalizer
import java.util.Locale

object ChartMatcher {
    private val titleSuffix=Regex("(?i)[(（](?:live|现场版|现场|原唱|伴奏|伴唱|高清|HD|MV|KTV|cover[^)）]*)[)）]")
    fun titleKey(title:String):String = Normalizer.normalize(title,Normalizer.Form.NFKC)
        .replace(titleSuffix,"")
        .lowercase(Locale.ROOT).filter{it.isLetterOrDigit()}
    fun artistKey(artist:String)=Normalizer.normalize(artist,Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter{it.isLetterOrDigit()}
    fun select(candidates:List<Song>,artists:List<String>):Song? {
        val keys=artists.flatMap{ArtistNames.members(it)}.map(::artistKey).toSet()
        val matches=candidates.filter{song->ArtistNames.members(song.artist).any{artistKey(it) in keys}}
        return matches.sortedWith(compareByDescending<Song>{it.favorite}.thenBy{it.edition.contains("伴")}.thenBy{it.title.length}.thenBy{it.id}).firstOrNull()
    }
}
