// 由 scripts/build-vo.mjs 生成，请勿手改（改 audio/vo-script.json 后重跑脚本）
export const VO = [
  {
    "id": "hook",
    "file": "vo/hook.mp3",
    "lines": [
      "一台播放器，能长成什么样？",
      "先看脸，再说别的。"
    ],
    "seconds": 5.568005,
    "chars": 22,
    "start": 0,
    "dur": 216,
    "voStart": 14
  },
  {
    "id": "deck",
    "file": "vo/deck.mp3",
    "lines": [
      "首页、播放页、歌词、歌单。",
      "这些界面，全都跑在同一份 Compose 代码上。",
      "手机竖着拿，桌面横着放，布局自己会变，观感始终一致。"
    ],
    "seconds": 14.064014,
    "chars": 64,
    "start": 216,
    "dur": 470,
    "voStart": 14
  },
  {
    "id": "spot",
    "file": "vo/spot.mp3",
    "lines": [
      "播放页把封面推到满屏，歌词一个字一个字亮起来。",
      "封面取色，连主题都跟着专辑走。",
      "桌面端切成双栏，左边列表，右边详情。",
      "窗口拖窄，右栏自动收起。"
    ],
    "seconds": 16.44,
    "chars": 68,
    "start": 686,
    "dur": 542,
    "voStart": 14
  },
  {
    "id": "provider",
    "file": "vo/provider.mp3",
    "lines": [
      "更狠的一点，它不内置任何音源。",
      "写个 manifest 文件，实现搜索和取直链两个接口，",
      "打包拖进去，界面里立刻多出这个音源。",
      "声明了评论，评论才出现；没声明云盘，云盘就不出现。",
      "HTTP、JNI、独立二进制，三种跑法，随你挑。"
    ],
    "seconds": 23.688005,
    "chars": 110,
    "start": 1228,
    "dur": 759,
    "voStart": 14
  },
  {
    "id": "outro",
    "file": "vo/outro.mp3",
    "lines": [
      "界面归你，音源也归你。",
      "CPPlayer，开源，Apache 2.0。"
    ],
    "seconds": 6.48,
    "chars": 34,
    "start": 1987,
    "dur": 243,
    "voStart": 14
  }
] as const;

export const TOTAL = 2230;
export const FPS = 30;
export const SPEECH_SECONDS = 66.24;
