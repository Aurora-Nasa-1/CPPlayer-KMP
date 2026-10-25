package cp.player.core.lyrics

/**
 * 常用汉字 / 人名用汉字的「音读拍数 · 训读拍数」速查表。
 *
 * ## 为什么长这样
 * 逐字时长必须按**拍数**走：`涙`(なみだ) 是 3 拍、`の` 是 1 拍，若都按 1.0 参与分配，
 * 日语行里汉字永远被压短、假名永远被拖长 —— 这是现在日语行最明显的失真。
 *
 * 但把 2865 条写成 `"漢" to 2` 会让源文件涨到 40KB 级、常量池也膨胀。
 * 这里用**等长索引 + 6 bit 打包**：
 * - [KANJI]  与 [PACKED] 逐字符一一对应，下标即字；
 * - [PACKED] 每字符是 `ALPHABET` 的下标 `v`：`v shr 3` = 音读档（0 表示无音读，1..4 即拍数），
 *   `v and 0b111` = 训读拍数（0 表示无训读）；
 * - 两串常量常驻 ≈ 5 KB；首次查询再建一次 `HashMap`（2865 项，
 *   装箱后约 150 KB 堆），之后 O(1)。比起多背一个分词器（11~70 MB）可以忽略。
 *
 * ## 读哪个
 * 调用方判据（见 [moraFor]）：**汉字后接假名 ⇒ 训读，孤字 ⇒ 训读，汉字连写 ⇒ 音读**。
 * 这是日语书写的基本规则，比任何静态字典都准；遇到熟字训（今日=きょう）仍会错，
 * 那是词表要解决的事，不在本表职责内。
 *
 * ## 数据来源
 * Unicode Unihan `kJapanese` + `kGradeLevel`（Unicode License v3，与 Apache-2.0 兼容）。
 * **由 `scripts/gen_kanji_mora.py` 生成，不要手工编辑本文件。**
 */
internal object KanjiMoraTable {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"

    /** 与 [PACKED] 等长，逐字对应。 */
    private const val KANJI =
        "一丁七万丈三上下不与丐且世丙丟両並中串丸主久之乏乒乓乖乗乘乙九乞也乱乳乾亀亂了争事二互五井些亜亞亡交享京亭亮人什仁仇今介仏仔他仗付仙代令以仮仰件任份仿伍伏休伙会伝伯估伴伶伸伺似但佈位低住体何余佛作你佣佩佰佳併使侄來侈例侍供依価侮侵便係促俊俐" +
        "俗俘保俠信修俯俱倆倉個倍們倒倖候倚借倡倣倦倫倹值假偉偏做停健側偶偷偽傅傍傑傘備傢催傭傲傳債傷傻傾僅像僑僕僥僱僵價僻儀億儉儘儡優儲允元兄充兇先光克兌免児兒兔党兜兢入內全兩八公六共兵其具典兼冀円冊再冒写冠冤冥冬冰冶冷准凋凌凍凜凝几凡処凰凱凳凶凸" +
        "凹出函刀分切刊刑划列初判別刨利刪刮到刷券刺刻則削前剎剖剛剝剣剤剩剪副剰割創剿劃劇劈劍劑力功加劣助努劫励労効勁勃勇勉勒動務勝勞募勢勤勧勵勸包匆化北匙匠匪匯匹区医匿區十千升午半协卑南単博卞卧印危即卷卸卻厚原厭厲厳去参又及友双反収叔取受叙叛叢口古" +
        "句另只叫召叮可台右号司吃各吆合吉吊同名后吐向君吝吞吟吠否吧吩含吮吱吵吸吹吻吼呀呆呈告呢周味呵呻呼命咀咊咐咒咖咬咳咽哀品哄哇哈員哥哨哭哺唉售唯唱啄商啊問啜啞啟啡啼喂喃喉喊喔喘喚喜喝喧喪喬單喱喳営喻嗅嗇嗎嗚嗜嗽嘈嘔嘗嘱嘴嘹嘿器噪噬噴嚇嚥嚨嚮嚴嚷" +
        "嚼囂囉囊囑囚四回因団困囱囲図固国圃圈國圍圏園圓圖團土圧在地圾坊坐坑坡坦垂垃型垕埂埃埋城域埠執培基堂堅堆堕堡堤堪堰報場塁塊塌塑塔塗塘塞塢填塵境墅墊墓増增墟墨墬墮墳墾壁壇壊壓壘壞士壮壯声売壺壽変夏夕外多夜夠夢夥大天太夫央失夾奇奉奏契奔套奢奪奮女" +
        "奶奸她好如妒妙妥妨妹妻姊始姐姑姓委姨姪姻姿威娃娘娛娶婆婉婚婦婿媒媚媳媽嫁嫂嫉嫌嫩嬉嬌嬰嬸子孔字存孝季孤学孩孫學它宅宇安完宗官定宜宝実客宣室宮害宴宵家容宿寂寄密富寒寓寗寛寞察寡實審寫寬寵寶寸寺対寿封専射将將專尊尋對導小少尖尚尤尬就尷尺尽尾局居" +
        "屆屈届屋屍屏屑展屜屠屢層山岡岩岸峭峯島崇崩嶺嶼巖巡巢巣工左巧巨差己已巴巷巻巾巿布帆希帕帖帚帝師席帯帰帳帶常帽幅幕幫干平年幸幹幻幼幽幾庁広庇序底店府度座庫庭康廁廃廈廉廊廚廟廠廢廣廳延建弁弃弊弔引弟弧弱張強弾彈彎当形彩影彷役彼彿往征径待很律後徑" +
        "徒従得徘徙從復循微徳徴徵德徹徽心必忌忍志忘忙応忠快念忽忿怎怒怕怖思怠怡急性怨怪怯恃恆恋恍恐恒恕恢恤恥恨恩恭息恰恵悄悔悟悠患悦悩悪悲悴悶悼情惑惜惟惠惡惦惨惰惱想惶惹愁愈愉意愚愛感愧慄慈態慌慎慕慘慚慢慣慧慨慫慰慳慶慷慾憂憎憐憑憔憤憲憶憾懂懇懈應" +
        "懊懐懦懲懶懷懸懼戀成我戒或戚截戴户房所扁扇手才扎扒打托扣扭扮扯扶批找承技抄把抒抓投抖抗折抜択披抬抱抵抹押抽担拆拇拉拋拌拍拒拔拖拘拚招拜拝拠拡括拯拱拳拾拿持指按挑挖挟挨挫振挺挽挾挿捉捏捐捕捜捧捨捱捲捷掀掃授掉掌排掘掙掛掠採探掣接控推掩措掲描提" +
        "插揚換握揩揭揮援揺損搖搗搜搬搭搶摂摘摧摩摸摺撃撇撒撓撕撞撥撫播撲擁擄擅擇擊擋操擒擔據擠擦擬擲擴擺擾攀攔攘攜攝攤攪支收改攻放政故效敍敏救敗教敢散敦敬数敲整敵敷數斃文斉斑料斜斟斤斥斧斬断新斷方於施旁旅旋族旗既日旧旨早旱旺昂明昏易星映春昨是昼時晚" +
        "晝晦晨晩普景晴晶智暁暇暈暑暖暗暢暦暫暮暴曆曉曙曝曬曲更曽曾替最會月有朋服朗望朝期朦朧木未末本朵李材村杖杜束条来杯東松板枉析枕林果枝枯枱架柄柏某染柔柚柱柳柴柵栄校株核根格栽桂桃框案桌桑桶條梢梨梯械梳棄棉棋棍棒棚森棲棵椅植検楊楚業極楷楼楽概榜榦" +
        "榨榮構槍様樂樑樓標模樣権横樸樹樽橋橘橙機橫檔檢櫃櫥欄權欠次欣欧欺欽款歇歉歌歎歐歓歡止正此步武歧歩歪歯歲歴歸歹死殊残殖殘殴段殷殺殻殼殿毀毅毆母毎每毒比毛毫民気氛氣氧水永汁求汗江池污汪決汽沈沒沖沙沫河沸油治沿況泄泉泊法泛泡波泥注泳洋洗洞洩洪洲洶" +
        "活洽派流浄浅浜浞浩浪浮海浸消涉涕涙涯液涼淋淒淘淚淡淨淪深混淹淺添清渇渉渓渙減渠渡渣温測港渴游渺湖湧湯湾満溉源準溜溝溢溪溺溼滅滋滓滔滞滯滲滴滾滿漁漂漆漏演漠漢漫漱漲漸漾漿潑潔潛潜潤潦潮潰澀澄澆澈澡澳激濁濃濘濤濫濱濺濾瀉瀑灌灒灘灣火灯灰災炉炎炒" +
        "炬炭炮炸点為烈烏烘烟烤烹焚無焦焰然焼煎煙煥煮熄熊熟熨熱燃燈燒燕燙營燥燦燬燭爆爐爛爪爬爭爲爵父爸爺爽牀牆片版牌牙牛牠牢牧物牲特牽犠犧犯状狀狂狐狗狠狡独狭狸狹狼狽猛猜猟献猴猾獄獅獎獣獨獲獵獸獻率玉王玩玫玻珍珠班現球理琴瑣瑰璃環瓜瓣瓦瓶瓷甘甚甜生" +
        "產甥用田由甲申男画界畏畔留畜畢略番畫異畳當畸疊疑疫疲疼疾病症痊痕痘痛痩痰痴瘉瘋瘓瘡瘦療癒癡癢癱発登發白百皂的皇皮皺盃盆盈益盒盗盛盜盡監盤盪目盲直相盼省眉看県真眠眺眼眾着睛睡督瞎瞞瞧瞪瞭矛知矩短矮矯石砌砍研砕砰砲破硬碌碎碑碗碟碧碰確碼磁磅磋磚" +
        "磨礎礙礦示礼社祈祕祚祝神祟祥票祭禁禍福禦禮禱禽禾秀私秋科秒秘租秩称移稀程稍税稚稟種稱稲稻稽稿穀積穎穏穩穫穴究空穿突窃窄窓窖窗窟窩窮竊立站竜竟章童端競竹竿笑笛符笨第筆等筋筒答策筵筷箏算管箭箱節範篇築簇簡簽簾簿籃籌籍籠籬籲米粉粒粗粘粛粥粵精糊糕" +
        "糖糟糧糰糸系糾紀約紅紊紋納紐純紗紙級紛素紡索紥紫紮累細紳紹終組絆経結絕絡給絨絮統絲絵綁綉經継続綜綠綢綪維綱網綴綿緊総緑緒線緝緣編緩練緻縁縄縛縣縦縫縮縱總績繁繊織繞繡繩繪繫繳繼續纏纖纜缶缸缺罐罩罪置罰署罵罷羅羊美羞羡羣羨義羽翁翅習翔翠翩翻翼耀" +
        "老考者而耍耐耕耗耳耽聊聖聘聚聞聯聰聲聳聴職聽聾肅肉肌肚股肢肥肩肪肯育肺胃胆背胖胞胡胸能脂脅脆脈脊脖脣脫脳脹脾腐腔腦腫腰腳腸腹腿膀膏膚膝膠膨膩膽臂臉臓臘臟臥臨自臭至致臺舅與興舉舊舌舍舒舖舞航般舵船艇艘艙艦良艱色艶芒花芳芸芽芾苗苛若苦英茂茅茎茫" +
        "茶草荒荔荘荷莊莖莽菊菌菓菜華萄萌萎萬落葉著葡葬葱蒸蒼蓄蓋蓮蔗蔚蔬蔭蔴蔵蔽蕉蕩蕪薄薪薬藉藍藏藝藥藹蘋蘭虎虐處虚虛虜號虧虫虱虹蚊蚤蛀蛇蛋蛍蛙蛛蛤蛮蜂蜓蜘蜜蜻蝕蝨蝴蝶蝸融螞螢蟬蟲蟹蠅蠟蠢蠻血行術街衛衝衞衡衣表衫衰衷袋袍袖被裁裂装裏裕裙補裝裳裸裹複" +
        "褥褲褸襟襤襪襯襲西要覆覇見規覓視覚覧親観覺覽觀角解触觸言訂計訊討訓託記訣訪設許訳訴診註証詐評詞詢試詩詫詭話該詳誇誉誌認誓誕誘語誠誡誣誤誦說読誰課誼調談請諒論諧諾謀謂謊謎謙講謝謠謡謹證識譜警譬譯議譲護譽讀變讓讚谷豆豈豊豐豓象豪豬貌貍貓貝負財貧" +
        "貨販貪貫責貯貴買費貼貿賀賂賄資賊賓賛賜賞賠賢賣賤質賬賭賴賺購賽贈贊贏贓贖走赳起趁超越趕趟趣趨足趾跋跌跑跛距跟跡跨跪路跳践踏踐踢踪踫踴蹄蹈蹟蹤蹲躁躇躊躍身躬躭躲躺軀車軌軍軟転軽較載輔輕輛輝輩輪輯輸輻輾輿轉轟辛辜辞辦辨辭辯辰辱農辺迅迎近返迫述迴" +
        "迷迹追退送逃逆透逐逓途逕逗這通逛逝逞速造逢連週進逸逼逾遅遊運遍過道達違遙遞遠遣適遭遮遲遵遷選遺避邀還邊邏那郊郎部郵都鄉鄭鄰配酒酔酣酬酷酸醉醑醜醫醬醸釀采釈釋重野量金釘針釣鈍鈎鈔鈕鈴鉄銀銅銜銭銳銷鋒鋤鋪鋳鋸鋼錄錢錦錬錯録錶鍊鍋鎖鎮鏟鏡鐘鐵鑄鑲" +
        "鑼鑽鑿長門閃閉開閒間閘関閣閨閱闊闖關闢防阻阿附降限院陣除陥陪陰陳陶陷陸険陽隆隊階随隔隙際障隠隧隨險隱隻雀雄雅集雇雌雑雕雖雙雜雞離難雨雪雲零雷電需震霉霊霎霓霞霧露霸靈青静靜非靠面革鞋鞏鞠鞭音韻響頁頂項順須頌預頑頒頓領頭頰頸頹頻頼顆題額顏顕願顛" +
        "類顧顯風颱飄飛食飢飪飯飲飼飽飾餅養餌餐餒餓餘館餵饒首香馬馳馴駁駆駐駕駛駝駭駱騎騒験騙騰騷驅驕驗驚驟骨骯骸髒體高髦髪髮鬆鬚鬥鬧鬼魂魄魔魚鮮鱗鳥鳴鴉鴨鴿鵝鶯鷹鹹鹼鹿麗麥麦麪麻麼黃黄黏黑黒默黙點黨鼓鼠鼻齊齒齡齢龍龐龜"

    /** 每字符 6 bit：`v shr 3` 音读档（0 = 无），`v and 0b111` 训读拍数。 */
    private const val PACKED =
        "IJIJIHIAJ9IAHJGJIIIIAK9KGGJIIIJI9JHJIKJKAABAHKIIIIJJKIIGGIIIJHAIASHIBIJJJGJ8IJ8HIIBIGJCAI8BJIJAAJIBGIGIJAGIBJBIABCJJJKKC" +
        "GBJIJKBBGI8IGJKIAJKJIIK0ABKAKJIJJAIIKIC8KJJJII8KJJGJG8JBJAILIGJAIIHJGIJIKJHH8IJKIIKJHIHIJACIJDIIKJBJGJIJAILJJJGIBJAKKGJI" +
        "IIIJJIJIDKAJJGAJJJIJIIIJIGIJIJJJIIJIIKJKIJJJJCJAALJKIJKJKGJJIKBKKKJKJLBIAJGJIGJJGHHIAJ0KJJJGAJCIIBKJIJKKAIIJIIIKIIIBJKIB" +
        "GGIIIGIJIIBJKGIIIJHIAIIJIJII8GJI8GIIKJ9JJJ8JIBJIJAC8B8IIIJIGGGIAJIBIIIJKJ8IJIGGI8IIJGIICJMHJJ88KBIJ8ACIMIJJCGJBJIIJIIJKG" +
        "IMBJJKHJIJJGJ9BIBIIBGIJBJAKIAKIAIBJJGIGGIJHJBIIAKJLBJJJIJHJKGAGAJJ8JIJDJAIIAI8KIJIGJKJJCJJIIAKJAIIBHGIMIIJIJKJILJJJGBJJH" +
        "GJ8IABIBLIIABACJAAIGBBGJCJAKGAIKBIAAKJIJCMJG9IAIGABJKIJAIAJKIJJAJHKIIIJJI9JIIAJIJGJJKIBJLBJGJHAJKJKIIKKJIJKHIIIIGIGKJ9JA" +
        "KJJHBKIJGBCKIIIIKIIJJIAJJHHIBJCIA9BJIIIAHAJGJJAIIBIIIGHIIIIIIIKKJAGJBBII8IAAIJJJ8IGJGGKJGIIJBKKIK8JIJIIIJIIIKJ9GIIIIJIIJ" +
        "AIIKBKIJBGIJGJAJKAJBKLKILIKJJBKBJKCJIJKJBIIGJIBJKAJLMIKJKJBJJKJICJJJJJJIJJGJCJJKIJBKJBJGAKDIKKBJJJJJJGCJKGGIJKIKLIJJGKKK" +
        "JJJJKLJCII9KIJIK9IAGJHIGGIJIJKACIGJAI9AJJGJIIJBKIJJIIJQKJJGIBIAKGJJJAKJJKJIBAAKJKJGBIGIJIKJJAJKBGIIGIKILIIIIJIJIIIIJIKJJ" +
        "IJJJJKGIIIJIJJIIIIJAIIIIIJGIIBIIJALJIJIJJIIIBJKJJJJJLIJJIKLJIJAIBIJJIJIJKIJKIAKJIJJJBIIIAIIIII9AJAJJABHIAHJJJIIJIJIJIIAI" +
        "IJIICIIK8JAIBKIIJKBJJKCJJIIJJJHIIIIKKII8JJHBIIABGIIAIIIKJIIJIJJIABGAHJKIJIJJIJIKAIHJJJIJJJIIIJIJIIBI8GGIIIG8JKJBIJGKJJII" +
        "GKJIIKGKIAIIII9IIKKAIGKA8KJIAKICKJIMAJIKKAIABB8BJHIIBJAAJJJIGJIIIIBBIIIIJIHJI9G8GIJIJIHIBGIGJJIAIAIJJIKJJJQJIAIIJIJIJGHI" +
        "JKJJJJIGJIIIIIJIJIGJKKKJJJJJJJJIIJJIJIAB8KJJJJGCIHJIJKIIIJIKJKAIJLLJJJJJKJIJGIJJKJKJGLJJJLIJGIJJJIKJIJIKIJAAIGGHJ9HIKBJI" +
        "8IJGGAKJGGGIIAJJIIIJKAIIJIJIHIJLKJKAKJBKIAKAKAAAJIIIII9I8JIIKJICCJJJJBIJJJHBJKGJJILIMJ8KJJIIJLKIILG8LAJJIAICG8HAIRIAJKJH" +
        "IIJHIJIJHJJIJIJIJJAJJ8JKJBJIIGGIJJJIBBGGIJJBBJGJJJIIIIJAIKIJIIJIJJIJJHJIIGIAIJHJKHJIJJIJJGJKIAAKJJIJIIJGKBJGJCGGIGJ88OBJ" +
        "BCLKBIABJCJIJKGJKCKBGJIAKBIAGJGGJBAIIGCJIKIIKILIGKKIIKIJIJJIKGJAKJIGIJIJHIIIIIBJIIHIIJIJGIIIHIIIGIJIIHGIKIIAGIHIAJKI0KIK" +
        "IIIJIIJAKIKIJIGKAIJAJIKCKJJKIJAJIIIJJGAIAHGKIIIHJJGIIIJIKIJ9IJIIJICIIJJIIJIJIJGIJKIHJIIJJGKIGJIJIAGBCJAJAJ8IJA9JAJIJLIJK" +
        "ILAJ8JKIIJKRIKIKGIKIJIGKIAAABAIJJJI9IHJIBIJAIJIHJKIGKGBGGJIIKIIGJAIJKBIAGKGKAJAJJBJJKJBJIJB8AJJAIIIGLIJIIIAJI9GIBIBIJHIG" +
        "9IIGK9KIIIJ8HIGJJJJHI8KIIILIIDMCI8IJGJAJJJIIIIJGKIAKBCCBJBIJIHI8AGJJ8KJIGAIGKJ8ILJ8JIIIIGKJHIIIJIJJAJGLIJII9IIKAJHCKHBBK" +
        "JBGIIGJJIIJLIIKAJIIIKIIIIJJIJIIIIIBJIIAIKIGJKJBIJAACJIKBBBJJKJBJKBCIIII8AJJIJIJKJAJILGCJJKJI8KBIBJBBIJJJIIIIJIILCIBIIAJK" +
        "BJKIIDBIJIJBCKAKKKBJKJIIIGAJJKGJKIGKJGAIIIGGKJIAIKJBBJIJDHIIIIIGGJJIIIKJKKJHHJ8G9BBJKJJKIAJGAJHKBHJAKKJABJJBJILGHJKJJJJG" +
        "JIIKJJIIIKAJKIJGILJIIJJIJJJAJJKAIJAJKJIIICBKJJJBKIJBBGGHGBIGJJIIKJJHIDIJJJJIIIH9IIIIIJIGJIKKKJIKIIAAICJJIJIJJGIABJJJIKIG" +
        "AIIIIKJIHHKIIIKJKIJJBHIIJGGBJKIJIKIKHJIIKKIIJIIKKKJJHAJBHJIIJJKIJAIIIKKIJGIGIBIILIIIJBIIIJJIIHJJJKJKIJCKIJIIIIKKJIJIIKJK" +
        "JDKIGLAIBIIIAJJIK9GJBBI8JIH9BIJIKAB8KKAJJJJJJJJKJIGJGJIKIIHALLAKK8AKJIIBII8KIJGHIIIJA8HHJIIJJGJABAJHJJIJI"

    private val index: Map<Char, Int> by lazy {
        HashMap<Char, Int>(3820).apply {
            for (i in KANJI.indices) put(KANJI[i], i)
        }
    }

    /** 该字是否在表内（表外汉字一律按 1 拍处理）。 */
    fun contains(ch: Char): Boolean = index.containsKey(ch)

    /** 音读拍数；无音读返回 0。 */
    fun onMora(ch: Char): Int = (packOf(ch) ?: return 0) shr 3

    /** 训读拍数；无训读返回 0。 */
    fun kunMora(ch: Char): Int = (packOf(ch) ?: return 0) and 0b111

    /**
     * 按上下文取拍数。
     *
     * @param followedByKana 该字后面紧跟假名（含送假名）⇒ 走训读。
     * @param inKanjiRun 该字左右还有汉字 ⇒ 走音读。
     */
    fun moraFor(ch: Char, followedByKana: Boolean, inKanjiRun: Boolean): Int {
        val on = onMora(ch)
        val kun = kunMora(ch)
        val pick = when {
            followedByKana || !inKanjiRun -> if (kun > 0) kun else on
            else -> if (on > 0) on else kun
        }
        return if (pick > 0) pick else 1
    }

    private fun packOf(ch: Char): Int? {
        val i = index[ch] ?: return null
        val v = ALPHABET.indexOf(PACKED[i])
        return if (v < 0) null else v
    }
}
