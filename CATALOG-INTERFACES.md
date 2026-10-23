# 曲库接口约定

- MetadataProvider.parse(name,path,rule) → LibraryMetadata(title,artist,language,genre,edition,provider)。纯本地解析，可插入新命名规则。
- Drive115.list(cid,offset) → DrivePage。分页远程目录接入点。LibraryDb.commitPage复用解析管线并保存扫描断点。
- CatalogRepository.organize(progress)、backup(progress)、restore(pickCode) 是应用服务层；不依赖UI组件。
- CatalogCodec v2是设备交换格式；保留115稳定引用与元数据。导入要求登录相同115账号。
- JS本地桥 GET /library/status 获取曲库数量和解析版本；GET /browse/languages、/browse/tags 提供分类；GET /browse/songs 支持 artist/language/tag/offset。
- /browse/tags 中标签为 分类：流行、版本：DVD伴唱、目录：目录名等。参数经SQLite绑定，不拼接用户字段。
- 不启动HTTP服务器，不开放监听端口。将来增加其他云盘时实现资源提供层，并使用独立的提供方标识与账号隔离，不能直接复用115 pickcode。
