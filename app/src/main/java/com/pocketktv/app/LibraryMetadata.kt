package com.pocketktv.app

/** Stable provider contract: future naming conventions and cloud sources plug in here. */
data class LibraryMetadata(val title:String,val artist:String,val language:String="",val genre:String="",val edition:String="",val provider:String="filename-v7")
interface MetadataProvider { val id:String; fun parse(name:String,path:String,rule:String):LibraryMetadata }
object FilenameMetadataProvider:MetadataProvider {
 override val id="filename-v7"
 private val languages=setOf("国语","粤语","台语","闽南语","英语","日语","韩语","其它","其他","客家语","法语","泰语","德语","西班牙语","蒙语","藏语")
 private val genres=setOf("流行","合唱","怀旧","民歌","革命","儿歌","戏曲","粤曲","喜庆歌","舞曲","摇滚","民族","军旅","校园","佛教","宗教","轻音乐")
 override fun parse(name:String,path:String,rule:String):LibraryMetadata {
  val ext=name.substringAfterLast('.',"")
  var base=name.substringBeforeLast('.',name).replace('－','-').replace('—','-')
  base=base.trim().trimEnd('-',' ').replace(Regex("-国语MPG$",RegexOption.IGNORE_CASE),"-国语").replace(Regex("[（(]闽南[）)]$"),"(闽南语)")
  var language="";var genre="";var edition=""
  // Parse only a recognized suffix chain; leave dashes inside real titles intact.
  val parts=base.split('-').toMutableList()
  while(parts.size>2){val raw=parts.last().trim();val clean=raw.replace(Regex("[（(]\\d+[）)]$"),"").let{if(it=="国")"国语" else it}
   when {clean in languages->{language=clean;parts.removeAt(parts.lastIndex)}
    clean in genres->{genre=clean;parts.removeAt(parts.lastIndex)}
    clean.matches(Regex("(?i)(?:DVD|HD|VCD|BD)?(?:原唱|伴唱|伴奏|消音)"))->{edition=clean;parts.removeAt(parts.lastIndex)}
    raw.matches(Regex("\\d{5,8}"))&&parts.size>3&&parts[parts.lastIndex-1].trim() in languages->parts.removeAt(parts.lastIndex)
    else->break
   }
  }
  if(parts.size>=3&&parts.first().trim() in languages){language=parts.removeAt(0).trim()}
  base=parts.joinToString("-").trim()
  val langSuffix=Regex("[（(](${languages.joinToString("|")})[）)]$").find(base)
  if(langSuffix!=null){language=langSuffix.groupValues[1];base=base.removeRange(langSuffix.range)}
  val (rawTitle,artist)=SongNames.metadata("$base.$ext",path,rule)
  var title=rawTitle.trimStart(' ','-')
  if(title.contains("无原唱")){if(edition.isBlank())edition="伴唱";title=title.replace(Regex("[（(]无原唱[）)]"),"")}
  // A language alone is never a real title: retain the unresolved source rather than invent an artist.
  if(title in languages)return LibraryMetadata(base,"",language,genre,edition,id)
  return LibraryMetadata(title.trim().ifBlank{rawTitle},artist,language,genre,edition,id)
 }
}
object LibraryMetadataPipeline {
 const val VERSION="7"
 val providers:List<MetadataProvider> = listOf(FilenameMetadataProvider)
 fun parse(name:String,path:String,rule:String="artist_title")=providers.first().parse(name,path,rule)
}
