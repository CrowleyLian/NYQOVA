# 架构与阶段边界

```text
MainActivity：TV 双栏界面 / 遥控器 / 文件浏览 / 播放控制
  ├─ TvLibraryPane：专辑墙 / 分类 / 歌单 / 分页查询 / 遥控器焦点
  │   └─ LibraryIndexing + LibraryScanner + Room：分批目录索引 / CUE 去重 / 持久标签待办
  ├─ LyricsActivity：LRC 时间轴 / 双语歌词 / 整轨偏移
  │   └─ LyricsRepository：本地 → 缓存 → LRCLIB / 候选版本
  ├─ OnlineMedia：独立公共 HTTP 客户端 / MusicBrainz + CAA / 请求取消与缓存
  ├─ MusicSource：SMB / WebDAV 来源选择
  │   ├─ SmbSource：SMBJ 只读访问
  │   └─ WebDavSource + DavRequest：PROPFIND / 小型元数据读取
  └─ MediaController
      └─ PlaybackService：MediaSessionService + ExoPlayer
          └─ DefaultDataSource：本地 content URI
              └─ MusicDataSource：SMB 随机读 / 带认证的 HTTP Range

core：纯 Kotlin 的 CUE / LRC 解析与文本解码，独立 JVM 测试
```

浏览任务在独立执行线程上运行，旧请求通过版本号失效。播放与 Activity 生命周期分离，前台播放由 Media3 管理。各次 SMB 读取持有独立连接与文件句柄，关闭或失败时释放；没有向 NAS 写入的 API。

CUE 曲目保留原文件路径、起始微秒及结束微秒，通过 MediaItem clipping 加入同一队列。多 FILE 在文件切换处不继承前一文件的结束点。INDEX 00 不作为曲目播放起点。CUE 的相对路径不得逃出当前共享。

Room 的 tracks 保存来源指纹、目录、专辑键、标签、原文件、CUE 时间范围、封面缓存和歌词路径；directories 保存文件名、大小、修改时间组成的目录指纹。快速扫描只读取目录和小型 CUE/LRC，按完整文件夹事务提交基础曲目与 audio_index 待办。未变化的目录复用已有曲目记录，但仍遍历以检测变更。仅完整遍历成功后清理已经移除的目录和文件；取消或失败保留已提交批次与尚未访问的旧目录。未完成遍历标记和标签待办跨进程保存。

LibraryIndexing 使用独立于 UI 查询的串行工作线程，基础索引可播放后再读取音频标签和封面；Activity 销毁只移除监听，不销毁进程内任务。启动继续未完成工作，用户可暂停或重试。文件大小或修改时间变化才重新补全；单文件失败最多重试两次，连续网络读取失败暂停任务。WebDAV 标签读取使用有界 HTTP Range、单次请求超时及总读取预算，播放数据源的默认超时不变。数据库迁移 3→4 保留原曲库、收藏与歌单。

封面最多解码到 512px，扫描不保留原始内嵌图片。专辑墙使用 4MB 位图缓存和后台图片加载。MediaMetadataRetriever 通过受控随机读取接口取标签，不把整首音频载入内存。在线歌词/封面通过独立公共客户端接入，不共享 NAS 凭据；断外网不影响 NAS 播放，已有缓存仍可使用。

连接凭据由 Android Keystore + AES-GCM 加密保存在应用私有数据中，启动自动恢复。SourceDiscovery 在用户打开发现窗口时监听 DNS-SD 并探测当前私有网段。当前 HTTP 支持用于用户配置的局域网 WebDAV；正式使用可改成 HTTPS。没有内置 HTTP 服务器、手机网页或配对模块。
