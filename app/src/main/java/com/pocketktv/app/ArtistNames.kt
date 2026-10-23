package com.pocketktv.app

import java.text.Normalizer

/** Credits stay intact on songs; this parser builds the many-to-many singer index. */
object ArtistNames {
    const val VERSION="2"
    private val aliases=mapOf("twins" to "Twins", "beyond" to "Beyond", "soler" to "Soler", "s.h.e" to "S.H.E", "s.h.e." to "S.H.E")
    private val protected=mapOf("MC&HOTDOG" to "MC&HOTDOG", "X+DREAM" to "X+DREAM", "GD&TOP" to "GD&TOP", "R&B" to "R&B", "AC/DC" to "AC/DC", "SIMON & GARFUNKEL" to "Simon & Garfunkel", "EARTH, WIND & FIRE" to "Earth, Wind & Fire")
    private val whitespace=Regex("\\s+")
    private val hanVsSeparator=Regex("(?<=[\\p{IsHan}])(?i:vs\\.?)(?=[\\p{IsHan}])")
    private val featSymbolPrefix=Regex("(?i:feat)(?=[+&^])")
    private val creditSeparator=Regex("[+&^、,;/；]|\\s+(?i:featuring|feat\\.?|ft\\.?|vs\\.?)\\s*|(?<=[\\p{IsHan}])(?i:feat\\.?|ft\\.?)(?=[\\p{IsHan}])")
    private val creditPrefix=Regex("^(?i:feat\\.|ft\\.)\\s*|^(?i:feat|ft)\\s+")
    private val protectedPatterns=protected.entries.mapIndexed{i,entry->Triple("\uE000${i}\uE001",Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(entry.key)+"(?![\\p{L}\\p{N}])",RegexOption.IGNORE_CASE),entry.value)}
    fun members(raw:String):List<String> {
        val value=Normalizer.normalize(raw,Normalizer.Form.NFKC).replace(whitespace," ").trim()
        protected[value.uppercase(java.util.Locale.ROOT)]?.let{return listOf(it)}
        var safe=value
        val names=mutableMapOf<String,String>()
        protectedPatterns.forEach{(placeholder,pattern,name)->if(pattern.containsMatchIn(safe)){safe=pattern.replace(safe,placeholder);names[placeholder]=name}}
        val split=safe.replace(hanVsSeparator,"+")
            .replace(featSymbolPrefix,"+")
            .split(creditSeparator)
        return split.map{it.trim().replace(creditPrefix,"").trim()}.map{names[it]?:it}
            .filter{it.isNotEmpty()&&it !in setOf("佚名","群星","等","未知歌手","未识别歌手")&&it.uppercase(java.util.Locale.ROOT) !in setOf("FEAT","FEATURING","FT","VS")}
            .map{aliases[it.lowercase(java.util.Locale.ROOT)]?:it}.distinct()
    }
}

object LibraryLanguage {
    val chinese=setOf("国语","粤语","台语","闽南语","客家语","客家话","上海话","潮语","中文","华语")
    val foreign=setOf("英语","日语","韩语","法语","泰语","德语","西班牙语")
    fun condition(scope:String,column:String="language")=when(scope){
        "chinese"->"$column IN ("+chinese.joinToString(","){"'$it'"}+")"
        "foreign"->"$column IN ("+foreign.joinToString(","){"'$it'"}+")"
        else->"1"
    }
    fun order(column:String="language")="CASE WHEN ${condition("chinese",column)} THEN 0 WHEN ${condition("foreign",column)} THEN 2 ELSE 1 END"
}
