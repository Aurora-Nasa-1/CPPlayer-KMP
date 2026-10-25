package cp.player.core.lyrics

/**
 * GB2312 汉字的「声母类 · 韵母类 · 声调」速查表。
 *
 * ## 为什么需要它
 * 逐字时长要区分「字与字为什么不一样长」：`海`(hǎi) 是擦音起头 + 前响双元音 + 三声，
 * `的`(de) 是塞音 + 轻声 —— 两者在歌唱里的可延展性差一倍以上。
 * 之前靠一张手挑的几百字白名单猜（繁体、生僻、粤语词全落空），这里换成全量拼音。
 *
 * ## 编码
 * [KANJI] 与 [PACKED] 逐字对应，[PACKED] 每两字符装 9 bit：
 * `声母类(0..7) * 64 + 韵母类(0..7) * 8 + 声调(0..4)`。
 *
 * 声母类：0 零声母 · 1 不送气塞音 · 2 送气塞音 · 3 塞擦音 · 4 擦音 · 5 鼻音 · 6 边/日 · 7 半元音
 * 韵母类：0 大开 · 1 中 · 2 闭前 · 3 闭后 · 4 前响双元音 · 5 后响双元音 · 6 前鼻音 · 7 后鼻音
 *
 * ## 数据来源
 * Unicode Unihan `kMandarin` + `kIRG_GSource`（GB2312 G0/G1 共 8965 字；
 * 多音字取 Unihan 的首条读音）。**由 `scripts/gen_pinyin_timing.py` 生成，勿手改。**
 */
internal object PinyinTimingTable {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"

    private const val KANJI =
        "一丁七万丈三上下丌不与丐丑专且丕世丘丙业丛东丝丞丢两严丧丨个丫丬中丰串临丶丸丹为主丽举丿乃久乇么义之乌乍乎乏乐乒乓乔乖乘乙乜九乞也习乡书乩买乱乳乾亂了予争事二亍于亏云互亓五井亘亚些亞亟亠亡亢交亥亦产亨亩享京亭亮亲亳亵人亻亿什仁仂仃仄仅仆仇仉" +
        "今介仍从仑仓仔仕他仗付仙仝仞仟仡代令以仨仪仫们仰仲仳仵件价任份仿企伉伊伍伎伏伐休众优伙会伛伞伟传伢伤伥伦伧伪伫伯估伲伴伶伸伺似伽佃但佇位低住佐佑体何佗佘余佚佛作佝佞佟你佣佤佥佧佩佬佯佰佳佴佶佻佼佾使侃侄來侈侉例侍侏侑侔侖侗供依侠侣侥侦侧侨侩" +
        "侪侬侮侯侵便係促俄俅俊俎俏俐俑俗俘俚俜保俞俟俠信俣俦俨俩俪俭修俯俱俳俸俺俾倀倆倉個倌倍倏們倒倔倘候倚倜借倡倥倦倨倩倪倫倬倭倮债值倾偃假偈偉偌偎偏偕做停健偬側偵偶偷偻偾偿傀傅傈傍傖傘備傢傣傥傧储傩催傭傲傳傴債傷傺傻傾僂僅僉像僑僕僖僚僞僥僦僧僨" +
        "僬僭僮僳僵價僻儀儂億儆儇儈儉儋儐儒儔儕儘償儡優儲儷儺儻儼儿兀允元兄充兆先光克免兑兒兔兕兖党兜兢入全兩八公六兮兰共关兴兵其具典兹养兼兽冀冁冂内冈冉册再冒冕冖冗写军农冠冢冤冥冫冬冯冰冱冲决况冶冷冻冼冽净凄准凇凈凉凋凌凍减凑凛凝几凡凤凫凭凯凰凱凳" +
        "凵凶凸凹出击凼函凿刀刁刂刃分切刈刊刍刎刑划刖列刘则刚创初删判刨利别刭刮到刳制刷券刹刺刻刽刿剀剁剂剃剄則削剋剌前剐剑剔剖剛剜剞剡剥剧剩剪剮副割剴創剽剿劁劂劃劇劈劉劊劌劍劐劑劓力劝办功加务劢劣动助努劫劬劭励劲劳劾势勁勃勇勉勋勐勒動勖勘務勛勝勞募" +
        "勢勤勰勱勵勸勹勺勾勿匀包匆匈匍匏匐匕化北匙匚匝匠匡匣匦匪匭匮匯匱匹区医匾匿區十千卅升午卉半华协卑卒卓協单卖南博卜卞卟占卡卢卣卤卦卧卩卫卮卯印危即却卵卷卸卺卿厂厄厅历厉压厌厍厕厘厙厚厝原厠厢厣厥厦厨厩厭厮厲厴厶去县叁参參又叉及友双反发叔取受变" +
        "叙叛叟叠叢口古句另叨叩只叫召叭叮可台叱史右叵叶号司叹叻叼叽吁吃各吆合吉吊同名后吏吐向吒吓吕吖吗君吝吞吟吠吡吣否吧吨吩含听吭吮启吱吲吴吵吸吹吻吼吾呀呃呆呈告呋呐呒呓呔呕呖呗员呙呛呜呢呤呦周呱呲味呵呶呷呸呻呼命咀咂咄咆咋和咎咏咐咒咔咕咖咙咚咛咝" +
        "咣咤咦咧咨咩咪咫咬咭咯咱咳咴咸咻咼咽咿哀品哂哄哆哇哈哉哌响哎哏哐哑哒哓哔哕哗哙哚哜哝哞哟員哥哦哧哨哩哪哭哮哲哳哺哼哽哿唁唄唆唇唉唏唐唑唔唛唠唢唣唤唧唪唬售唯唰唱唳唷唼唾唿啁啃啄商啉啊問啐啓啕啖啜啞啡啤啥啦啧啪啬啭啮啵啶啷啸啻啼啾喀喁喂喃善喇" +
        "喈喉喊喋喏喑喔喘喙喚喜喝喟喧喪喬單喱喲喳喵喷喹喻喽喾嗄嗅嗆嗇嗉嗌嗍嗎嗑嗒嗓嗔嗖嗚嗜嗝嗟嗡嗣嗤嗥嗦嗨嗩嗪嗫嗬嗯嗲嗳嗵嗶嗷嗽嗾嘀嘁嘆嘈嘉嘌嘍嘎嘏嘔嘖嘗嘘嘛嘜嘞嘟嘣嘤嘧嘩嘬嘭嘮嘯嘰嘱嘲嘴嘵嘶嘸嘹嘻嘿噁噌噍噎噔噗噘噙噜噝噠噢噤噥噦器噩噪噫噬噯噱噲噴" +
        "噶噸噹噻噼嚀嚅嚆嚇嚌嚎嚏嚓嚕嚙嚣嚦嚨嚮嚯嚳嚴嚶嚷嚼囀囁囂囅囈囉囊囌囑囔囗囚四囝回囟因囡团囤囫园困囱围囵囹固国图囿圃圄圆圇圈圉圊國圍園圓圖團圜土圣在圩圪圬圭圮圯地圳圹场圻圾址坂均坊坌坍坎坏坐坑块坚坛坜坝坞坟坠坡坤坦坨坩坪坫坭坯坳坶坷坻坼垂垃垄" +
        "垅垆型垌垒垓垛垠垡垢垣垤垦垧垩垫垭垮垲垴垸埂埃埋城埏埒埔埕埘埙埚埝域埠埡埤埭埯埴執埸培基埽堀堂堅堆堇堊堋堍堑堕堖堙堝堞堠堡堤堪堯堰報場堵塄塊塋塌塍塏塑塒塔塗塘塞塢塤塥填塬塵塹塾墀墁境墅墉墊墒墓墙墚墜增墟墨墩墮墳墻墼墾壁壅壇壑壓壕壘壙壚壞壟壠" +
        "壢壤壩士壬壮壯声壳壶壹壺壽夂处备复夏夔夕外夙多夜够夢夤夥大天太夫夭央夯失头夷夸夹夺夼夾奁奂奄奇奈奉奋奎奏奐契奔奕奖套奘奚奠奢奥奩奪奬奮女奴奶奸她好妁如妃妄妆妇妈妊妍妒妓妖妗妙妝妞妣妤妥妨妩妪妫妮妯妲妹妻妾姆姊始姐姑姒姓委姗姘姚姜姝姣姥姨姬姹" +
        "姻姿威娃娄娅娆娇娈娉娌娑娓娘娜娟娠娣娥娩娱娲娴娶娼婀婁婆婉婊婕婚婢婦婧婪婭婴婵婶婷婺婿媒媚媛媧媪媲媳媵媸媽媾嫁嫂嫉嫌嫒嫔嫖嫗嫘嫜嫠嫡嫣嫦嫩嫫嫱嫵嫻嬀嬈嬉嬋嬌嬖嬗嬙嬡嬪嬰嬲嬴嬷嬸孀孌子孑孓孔孕字存孙孚孛孜孝孟孢季孤孥学孩孪孫孬孰孱孳孵學孺孽孿" +
        "宀宁它宄宅宇守安宋完宏宓宕宗官宙定宛宜宝实宠审客宣室宥宦宪宫宰害宴宵家宸容宽宾宿寂寄寅密寇富寐寒寓寝寞察寡寢寤寥實寧寨審寫寬寮寰寵寶寸对寺寻导寿封射将將專尉尊尋對導小少尔尕尖尘尚尜尝尢尤尥尧尬就尴尷尸尹尺尻尼尽尾尿局屁层居屈屉届屋屎屏屐屑展" +
        "屙属屠屡屢屣層履屦屨屬屮屯山屹屺屿岁岂岈岌岍岐岑岔岖岗岘岙岚岛岜岡岢岣岩岫岬岭岱岳岵岷岸岽岿峁峄峋峒峙峡峤峥峦峨峪峭峰峴島峻峽崂崃崆崇崍崎崔崖崗崛崞崢崤崦崧崩崬崭崮崴崽崾嵇嵊嵋嵌嵐嵘嵛嵝嵩嵫嵬嵯嵴嶁嶂嶄嶇嶗嶙嶝嶠嶧嶷嶸嶺嶼巅巋巍巒巔巛川州巡" +
        "巢工左巧巨巩巫差巯巰己已巳巴巷巽巾币市布帅帆师希帏帐帑帔帕帖帘帙帚帛帜帝帥带帧師席帮帱帳帶帷常帻帼帽幀幂幃幄幅幌幔幕幗幘幛幞幟幡幢幣幫幬干平年并幸幹幺幻幼幽幾广庀庄庆庇床庋序庐庑库应底庖店庙庚府庞废庠庥度座庫庭庳庵庶康庸庹庾廉廊廑廒廓廖廛廟" +
        "廠廡廢廣廨廪廬廳廴延廷建廾廿开弁异弃弄弈弊弋式弑弓引弗弘弛弟张弥弦弧弩弪弭弯弱弳張弹强弼彀彆彈彌彎彐归当录彖彗彘彙彝彡形彤彦彩彪彬彭彰影彳彷役彻彼往征徂径待徇很徉徊律後徐徑徒徕得徘徙徜從徠御徨復循徭微徵德徹徼徽心忄必忆忉忌忍忏忐忑忒忖志忘忙" +
        "忝忠忡忤忧忪快忭忮忱念忸忻忽忾忿怀态怂怃怄怅怆怊怍怎怏怒怔怕怖怙怛怜思怠怡急怦性怨怩怪怫怯怵总怼怿恁恂恃恋恍恐恒恕恙恚恝恢恣恤恧恨恩恪恫恬恭息恰恳恶恸恹恺恻恼恽恿悃悄悉悌悍悒悔悖悚悛悝悟悠患悦您悫悬悭悯悱悲悴悵悶悸悻悼情惆惊惋惑惕惘惚惜惝惟" +
        "惠惡惦惧惨惩惫惬惭惮惯惰惱惲想惴惶惹惺惻愀愁愆愈愉愍愎意愕愚愛愜感愠愣愤愦愧愫愴愷愾愿慈慊態慌慎慑慕慘慚慝慟慢慣慤慧慨慪慫慮慰慳慵慶慷憂憊憋憎憐憑憒憔憚憝憤憧憨憩憫憬憮憲憶憷憾懂懇懈應懊懋懌懑懒懔懞懟懣懦懨懲懵懶懷懸懺懼懾懿戀戆戇戈戊戋戌戍" +
        "戎戏成我戒戔戕或戗战戚戛戟戡戢戤戥戧戩截戬戮戰戲戳戴户戽戾房所扁扃扇扈扉手扌才扎扑扒打扔托扛扣扦执扩扪扫扬扭扮扯扰扳扶批扼找承技抄抉把抑抒抓投抖抗折抚抛抟抠抡抢护报抨披抬抱抵抹抻押抽抿拂拄担拆拇拈拉拊拌拍拎拐拒拓拔拖拗拘拙拚招拜拟拢拣拥拦拧" +
        "拨择括拭拮拯拱拳拴拶拷拼拽拾拿持挂指挈按挎挑挖挚挛挝挞挟挠挡挢挣挤挥挨挪挫振挲挹挺挽挾捂捃捅捆捉捋捌捍捎捏捐捕捞损捡换捣捧捨捩捫捭据捱捲捶捷捺捻掀掂掃掄掇授掉掊掌掎掏掐排掖掘掙掠探掣接控推掩措掬掭掮掰掳掴掷掸掺掼掾揀揄揆揉揍揎描提插揖揚換揞" +
        "揠握揣揩揪揭揮揲援揶揸揽揿搀搁搂搅搋搌損搏搐搓搔搗搛搜搞搠搡搦搪搬搭搴搶携搽搿摁摄摅摆摇摈摊摑摒摔摘摜摞摟摧摩摭摯摳摶摸摹摺摻撂撄撅撇撈撑撒撓撕撖撙撞撟撣撤撥撩撫撬播撮撰撲撳撵撷撸撺撻撼撾撿擀擁擂擄擅擇擊擋操擎擐擒擔擗擘據擞擠擢擤擦擬擯擰擱" +
        "擲擴擷擺擻擼擾攀攄攆攉攏攒攔攖攘攙攛攝攢攣攤攥攪攫攬攮支攴攵收攸改攻放政故效敉敌敏救敕敖敗教敛敝敞敢散敦敫敬数敲整敵敷數斂斃文斋斌斐斑斓斕斗料斛斜斟斡斤斥斧斩斫斬断斯新斷方於施旁旃旄旅旆旋旌旎族旒旖旗无既日旦旧旨早旬旭旮旯旰旱时旷旺昀昂昃昆" +
        "昊昌明昏易昔昕昙昝星映春昧昨昭是昱昴昵昶昼显晁時晃晉晋晌晏晒晓晔晕晖晗晚晝晟晡晤晦晨普景晰晴晶晷智晾暂暄暇暈暉暌暑暖暗暝暢暧暨暫暮暴暹暾曄曆曇曉曖曙曛曜曝曠曦曩曬曰曲曳更曷書曹曼曾替最會月有朊朋服朐朔朕朗望朝期朦朧木未末本札术朱朴朵机朽杀杂" +
        "权杆杈杉杌李杏材村杓杖杜杞束杠条来杨杩杪杭杯杰東杲杳杵杷杼松板极构枇枉枋析枕林枘枚果枝枞枢枣枥枧枨枪枫枭枯枰枳枵架枷枸柁柃柄柏某柑柒染柔柘柙柚柜柝柞柠柢查柩柬柯柰柱柳柴柽柿栀栅标栈栉栊栋栌栎栏树栓栖栗栝校栩株栲栳样核根格栽栾桀桁桂桃桄桅框案" +
        "桉桊桌桎桐桑桓桔桕桠桡桢档桤桥桦桧桨桩桫桴桶桷梁梃梅梆梏梓梗梘條梟梢梦梧梨梭梯械梳梵检棂棄棉棋棍棒棕棖棗棘棚棟棠棣棧森棰棱棲棵棹棺棼椁椅椋植椎椏椐椒椟椠椤椭椰椴椹椽椿楂楊楓楔楗楚楝楞楠楣楦楨楫業楮楱極楷楸楹楼榀概榄榆榇榈榉榍榔榕榘榛榜榧榨榪" +
        "榫榭榮榱榴榷榻榿槁槊構槌槍槎槐槔槛槟槠槧槭槲槳槽槿樁樂樅樊樓樗樘標樞樟模樣樨横樯樱樵樸樹樺樽樾橄橇橈橋橐橘橙橛機橡橢橥橱橹橼檀檄檉檎檐檑檔檗檜檠檢檣檩檫檬檯檳檸檻櫃櫓櫚櫛櫝櫞櫟櫧櫨櫪櫬櫳櫸櫻欄權欏欒欖欞欠次欢欣欤欧欲欷欹欺欽款歃歆歇歉歌歐歙" +
        "歟歡止正此步武歧歪歲歷歸歹死歼殁殂殃殄殆殇殉殊残殍殒殓殖殘殚殛殞殡殤殪殫殮殯殲殳殴段殷殺殻殿毁毂毅毆毋母每毒毓比毕毖毗毙毛毡毪毫毯毳毵毹毽毿氅氆氇氈氌氍氏氐民氓气氕氖氘氙氚氛氟氡氢氣氤氦氧氨氩氪氫氬氮氯氰氲水氵永氽汀汁求汆汇汉汊汐汔汕汗汛汜" +
        "汝汞江池污汤汨汩汪汰汲汴汶汹汽汾沁沂沃沅沆沈沉沌沏沐沓沔沙沛沟没沣沤沥沦沧沩沪沫沭沮沱沲河沸油治沼沽沾沿泄泅泉泊泌泐泓泔法泖泗泛泞泠泡波泣泥注泪泫泮泯泰泱泳泵泶泷泸泺泻泼泽泾洁洄洇洋洌洎洒洗洙洚洛洞津洧洪洫洮洱洲洳洵洹活洼洽派流浃浅浆浇浈浊" +
        "测浍济浏浑浒浓浔浙浚浜浞浠浣浦浩浪浮浯浴海浸浹浼涂涅涇消涉涌涎涑涓涔涕涛涝涞涟涠涡涣涤润涧涨涩涪涫涮涯液涵涸涿淀淄淅淆淇淋淌淑淖淘淙淝淞淠淡淤淦淪淫淬淮深淳淵淶混淹淺添淼清渊渌渍渎渐渑渔渖渗渙渚渝渠渡渣渤渥渦温渫測渭港渲渴游渺渾湃湄湍湎湓湔" +
        "湖湘湛湞湟湫湮湯湾湿溃溅溆溉溏源準溘溜溝溟溢溥溧溪溯溱溲溴溶溷溺溻溽滁滂滄滅滇滋滌滎滏滑滓滔滕滗滚滞滟滠满滢滤滥滦滨滩滬滯滲滴滷滸滹滿漁漂漆漉漏漓演漕漚漠漢漣漤漩漪漫漬漭漯漱漲漳漶漸漾漿潁潆潇潋潍潑潔潘潙潛潜潞潢潤潦潭潮潯潰潲潴潷潸潺潼潿澀" +
        "澄澆澇澈澉澌澍澎澗澜澠澡澤澧澩澮澱澳澶澹激濁濂濃濉濑濒濕濘濛濞濟濠濡濤濫濮濯濰濱濺濼濾瀅瀆瀉瀋瀏瀑瀕瀘瀚瀛瀝瀟瀠瀣瀧瀨瀰瀲瀵瀹瀾灃灄灌灏灑灕灘灝灞灣灤灧火灬灭灯灰灵灶灸灼灾灿炀炅炉炊炎炒炔炕炖炙炜炝炫炬炭炮炯炱炳炷炸点炻炼炽烀烁烂烃烈烊烏烘" +
        "烙烛烟烤烦烧烨烩烫烬热烯烴烷烹烽焉焊焐焓焕焖焘焙焚無焦焯焰焱然煅煉煊煌煎煒煜煞煢煤煥煦照煨煩煬煮煲煳煸煺煽熄熊熏熒熔熗熘熙熟熠熨熬熱熳熵熹熾燁燃燈燎燒燔燕燙燜營燠燥燦燧燭燮燴燹燼燾爆爍爐爛爝爨爪爬爭爰爱爲爵父爷爸爹爺爻爽爾爿片版牌牍牒牖牘牙" +
        "牛牝牟牡牢牦牧物牮牯牲牵特牺牽牾牿犀犁犄犊犋犍犏犒犖犟犢犧犬犭犯犰犴状犷犸犹狀狁狂狃狄狈狍狎狐狒狗狙狞狠狡狨狩独狭狮狯狰狱狲狳狴狷狸狹狺狻狼狽猁猃猊猎猓猕猖猗猙猛猜猝猞猡猢猥猩猪猫猬献猱猴猶猷猸猹猻猾猿獁獄獅獍獐獒獗獠獨獪獫獬獭獯獰獲獵獷獸" +
        "獺獻獼獾玀玄率玉王玎玑玖玛玟玢玩玫玮环现玲玳玷玺玻珀珂珈珉珊珍珏珐珑珙珞珠珥珧珩班珲現球琅理琉琊琏琐琚琛琢琥琦琨琪琬琮琰琳琴琵琶琼琿瑁瑋瑕瑗瑙瑚瑛瑜瑞瑟瑣瑩瑪瑭瑰瑶瑷瑾璀璁璃璇璉璋璎璐璜璞璣璦璧璨璩環璺璽瓊瓏瓒瓔瓚瓜瓞瓠瓢瓣瓤瓦瓮瓯瓴瓶瓷瓿" +
        "甄甌甍甏甑甓甘甙甚甜生産甥用甩甫甬甭甯田由甲申电男甸町画甾畀畅畈畋界畎畏畔留畚畛畜畝畢略畦番畫畲畴當畸畹畿疃疆疇疊疋疏疑疒疔疖疗疙疚疝疟疠疡疣疤疥疫疬疮疯疰疱疲疳疴疵疸疹疼疽疾痂痃痄病症痈痉痊痍痒痔痕痖痘痙痛痞痢痣痤痦痧痨痪痫痰痱痴痹痼痿瘀" +
        "瘁瘂瘃瘅瘊瘋瘌瘍瘐瘓瘕瘗瘘瘙瘛瘞瘟瘠瘡瘢瘤瘥瘦瘧瘩瘪瘫瘭瘰瘳瘴瘵瘸瘻瘼瘾瘿癀療癃癆癇癉癌癍癔癖癘癜癞癟癢癣癤癥癧癩癫癬癭癮癯癰癱癲癸登發白百皂的皆皇皈皋皎皑皓皖皙皚皤皮皱皲皴皸皺皿盂盅盆盈益盍盎盏盐监盒盔盖盗盘盛盞盟盡監盤盥盧目盯盱盲直相盹" +
        "盼盾省眄眇眈眉看眍眙眚真眠眢眦眨眩眭眯眵眶眷眸眺眼着睁睃睇睏睐睑睚睛睜睞睡睢督睥睦睨睫睬睹睽睾睿瞀瞄瞅瞌瞍瞎瞑瞒瞘瞞瞟瞠瞢瞥瞧瞩瞪瞬瞭瞰瞳瞵瞻瞼瞽瞿矇矍矗矚矛矜矢矣知矧矩矫矬短矮矯石矶矸矽矾矿砀码砂砉砌砍砑砒研砖砗砘砚砜砝砟砣砥砦砧砩砬砭砰" +
        "破砷砸砹砺砻砼砾础硃硅硇硌硎硐硒硕硖硗硝硤硨硪硫硬硭确硯硷硼碇碉碌碍碎碑碓碗碘碚碛碜碟碡碣碥碧碩碭碰碱碲碳碴碸碹確碼碾磁磅磉磊磋磐磔磕磙磚磣磧磨磬磯磲磴磷磺磽礁礅礎礓礙礞礤礦礪礫礬礱礴示礻礼社祀祁祆祈祉祓祖祗祚祛祜祝神祟祠祢祥祧票祭祯祷祸祺" +
        "禀禁禄禅禊禍禎福禚禦禧禪禮禰禱禳禹禺离禽禾秀私秃秆秉秋种科秒秕秘租秣秤秦秧秩秫秭积称秸移秽稀稂稃稆程稍税稔稗稚稞稠稣種稱稳稷稹稻稼稽稿穀穆穌積穎穑穗穡穢穩穫穰穴究穷穸穹空穿窀突窃窄窆窈窍窑窒窕窖窗窘窜窝窟窠窥窦窨窩窪窬窭窮窳窶窺窿竄竅竇竈竊" +
        "立竖站竞竟章竣童竦竪竭端競竹竺竽竿笃笄笆笈笊笋笏笑笔笕笙笛笞笠笤笥符笨笪笫第笮笱笳笸笺笼笾筅筆筇等筋筌筏筐筑筒答策筘筚筛筝筠筢筧筮筱筲筵筷筹筻签简箅箋箍箏箐箔箕算箜箝管箢箦箧箨箩箪箫箬箭箱箴箸節篁範篆篇築篋篌篑篓篙篚篝篡篤篥篦篩篪篮篱篳篷篼" +
        "篾簀簇簋簌簍簏簖簞簟簡簣簦簧簪簫簸簽簾簿籀籁籃籌籍籜籟籠籤籩籪籬籮籲米籴类籼籽粉粑粒粕粗粘粜粝粞粟粢粤粥粪粮粱粲粳粹粼粽精糁糅糇糈糊糌糍糕糖糗糙糜糝糞糟糠糧糨糯糰糲糴糶糸糹系糾紀紂約紅紆紇紈紉紊紋納紐紓純紕紗紙級紛紜素紡索紧紫累細紱紲紳紹紺" +
        "紼紿絀終組絆絎結絝絞絡絢給絨絮統絲絳絶絷絹綁綃綆綈綉綏經綜綞綢綣綦綫綬維綮綰綱網綳綴綸綹綺綻綽綾綿緄緇緊緋緑緒緔緗緘緙緝緞締緡緣緦編緩緬緯緱緲練緶緹緻縈縉縊縋縐縑縛縝縞縟縣縧縫縭縮縱縲縴縵縶縷縹縻總績繁繅繆繇繒織繕繚繞繢繩繪繫繭繮繯繰繳繹繼" +
        "繽繾纂纈纊續纍纏纓纔纖纘纛纜纟纠纡红纣纤纥约级纨纩纪纫纬纭纯纰纱纲纳纵纶纷纸纹纺纽纾线绀绁绂练组绅细织终绉绊绋绌绍绎经绐绑绒结绔绕绗绘给绚绛络绝绞统绠绡绢绣绥绦继绨绩绪绫续绮绯绰绱绲绳维绵绶绷绸绺绻综绽绾绿缀缁缂缃缄缅缆缇缈缉缋缌缍缎缏缑缒" +
        "缓缔缕编缗缘缙缚缛缜缝缟缠缡缢缣缤缥缦缧缨缩缪缫缬缭缮缯缰缱缲缳缴缵缶缸缺罂罄罅罌罎罐网罔罕罗罘罚罟罡罢罨罩罪置罰罱署罴罷罹罾羁羅羆羈羊羌美羔羚羝羞羟羡群羥羧義羯羰羲羸羹羼羽羿翁翅翊翌翎習翔翕翘翟翠翡翥翦翩翮翰翱翳翹翻翼耀老考耄者耆耋而耍耐" +
        "耒耔耕耖耗耘耙耜耠耢耥耦耧耨耩耪耬耮耱耳耵耶耷耸耻耽耿聂聃聆聊聋职聍聒联聖聘聚聞聩聪聯聰聱聲聳聵聶職聹聽聾聿肀肃肄肅肆肇肉肋肌肓肖肘肚肛肜肝肟肠股肢肤肥肩肪肫肭肮肯肱育肴肷肺肼肽肾肿胀胁胂胃胄胆背胍胎胖胗胙胚胛胜胝胞胡胤胥胧胨胩胪胫胬胭胯胰" +
        "胱胲胳胴胶胸胺胼能脂脅脆脉脊脍脎脏脐脑脒脓脔脖脘脚脛脞脬脯脱脲脶脸脹脾腆腈腊腋腌腎腐腑腓腔腕腖腙腚腠腡腥腦腧腩腫腭腮腰腱腴腸腹腺腻腼腽腾腿膀膂膈膊膏膑膘膚膛膜膝膠膣膦膨膩膪膳膺膻膽膾膿臀臁臂臃臆臉臊臌臍臏臘臚臟臠臣臧臨自臬臭至致臺臻臼臾舀舁" +
        "舂舄舅舆與興舉舊舌舍舐舒舔舛舜舞舟舡舢舣舨航舫般舭舯舰舱舳舴舵舶舷舸船舻舾艄艇艉艋艏艘艙艚艟艤艦艨艫艮良艰艱色艳艴艷艹艺艽艾艿节芄芈芊芋芍芎芏芑芒芗芘芙芜芝芟芡芤芥芦芨芩芪芫芬芭芮芯芰花芳芴芷芸芹芻芽芾苁苄苇苈苊苋苌苍苎苏苑苒苓苔苕苗苘苛苜" +
        "苞苟苠苡苣苤若苦苧苫苯英苴苷苹苻茁茂范茄茅茆茇茈茉茌茎茏茑茔茕茗茚茛茜茧茨茫茬茭茯茱茳茴茵茶茸茹茺茼荀荃荆荇草荏荐荑荒荔荚荛荜荞荟荠荡荣荤荥荦荧荨荩荪荫荬荭荮药荷荸荻荼荽莅莆莉莊莎莒莓莖莘莛莜莞莠莢莧莨莩莪莫莰莱莲莳莴莶获莸莹莺莼莽菀菁菅菇" +
        "菊菌菏菔菖菘菜菝菟菠菡菥菩菪華菰菱菲菸菹菽萁萃萄萆萇萊萋萌萍萎萏萑萘萜萝萤营萦萧萨萬萱萵萸萼落葆葉葑葒著葙葚葛葜葡董葤葦葩葫葬葭葯葱葳葵葶葷葸葺蒂蒇蒈蒉蒋蒌蒎蒓蒔蒗蒙蒜蒡蒯蒲蒴蒸蒹蒺蒼蒽蒿蓀蓁蓄蓉蓊蓋蓍蓐蓑蓓蓖蓝蓟蓠蓣蓥蓦蓬蓮蓯蓰蓼蓽蓿蔌蔑" +
        "蔓蔔蔗蔚蔞蔟蔡蔣蔦蔫蔬蔭蔷蔸蔹蔺蔻蔼蔽蕁蕃蕆蕈蕉蕊蕎蕒蕓蕕蕖蕘蕙蕞蕢蕤蕨蕩蕪蕭蕲蕴蕷蕹蕺蕻蕾薄薅薇薈薊薌薏薑薔薛薜薟薤薦薨薩薪薮薯薰薷薹薺藁藉藍藎藏藐藓藕藜藝藤藥藩藪藴藶藹藺藻藿蘄蘅蘆蘇蘋蘑蘖蘚蘞蘢蘧蘩蘭蘸蘺蘼蘿虍虎虏虐虑虔處虚虜虞號虢虧虫" +
        "虬虮虱虹虺虻虼虽虾虿蚀蚁蚂蚊蚋蚌蚍蚓蚕蚜蚝蚣蚤蚧蚨蚩蚪蚬蚯蚰蚱蚴蚵蚶蚺蛀蛄蛆蛇蛉蛊蛋蛎蛏蛐蛑蛔蛘蛙蛛蛞蛟蛤蛩蛭蛮蛰蛱蛲蛳蛴蛸蛹蛺蛾蜀蜂蜃蜆蜇蜈蜉蜊蜍蜒蜓蜕蜗蜘蜚蜜蜞蜡蜢蜣蜥蜩蜮蜱蜴蜷蜻蜾蜿蝇蝈蝉蝌蝎蝓蝕蝗蝙蝠蝣蝤蝥蝦蝮蝰蝴蝶蝸蝻蝼蝽蝾螂螃螄" +
        "螅螈螋融螓螗螞螟螢螨螫螬螭螯螳螵螺螻螽蟀蟄蟆蟈蟊蟋蟎蟑蟒蟓蟛蟠蟣蟥蟪蟬蟮蟯蟲蟶蟹蟻蟾蠃蠅蠆蠊蠐蠑蠓蠕蠖蠛蠟蠡蠢蠣蠱蠲蠶蠹蠻蠼血衄衅衆衊行衍術衔街衙衛衝衡衢衣衤补表衩衫衬衮衰衲衷衹衽衾衿袁袂袄袅袈袋袍袒袖袜袢袤被袭袱袷袼裁裂装裆裉裊裎裏裒裔裕" +
        "裘裙補裝裟裢裣裤裥裨裰裱裳裴裸裹裼製裾褂複褊褐褒褓褙褚褛褡褥褪褫褰褲褳褴褶褸褻襁襄襇襖襝襞襟襠襤襦襪襬襯襲襻西要覃覆見規覓視覘覡覦親覬覯覲覷覺覽覿觀见观规觅视觇览觉觊觋觌觎觏觐觑角觖觚觜觞解觥触觫觯觳觴觶觸言訁訂訃訇計訊訌討訐訓訕訖記訛訝訟" +
        "訣訥訪設許訴訶診訾詁詆詈詎詐詒詔評詘詛詞詡詢詣試詩詫詬詭詮詰話該詳詵詹詼詿誄誅誆誇誉誊認誑誒誓誕誘誚語誠誡誣誤誥誦誨説誰課誶誹誼調諂諄談諉請諍諏諑諒論諗諛諜諞諢諤諦諧諫諭諮諱諳諶諷諸諺諼諾謀謁謂謄謅謇謊謎謐謔謖謗謙謚講謝謡謦謨謫謬謭謳謹謾證" +
        "譎譏譖識譙譚譜警譫譬譯議譴護譽讀變讎讒讓讕讖讜讞讠计订讣认讥讦讧讨让讪讫训议讯记讲讳讴讵讶讷许讹论讼讽设访诀证诂诃评诅识诈诉诊诋诌词诎诏译诒诓诔试诖诗诘诙诚诛诜话诞诟诠诡询诣诤该详诧诨诩诫诬语诮误诰诱诲诳说诵诶请诸诹诺读诼诽课诿谀谁谂调谄谅" +
        "谆谇谈谊谋谌谍谎谏谐谑谒谓谔谕谖谗谘谙谚谛谜谝谟谠谡谢谣谤谥谦谧谨谩谪谫谬谭谮谯谰谱谲谳谴谵谶谷豁豆豇豈豉豌豐豕豚象豢豪豫豳豸豹豺貂貅貉貊貌貔貘貝貞負財貢貧貨販貪貫責貯貰貲貳貴貶買貸貺費貼貽貿賀賁賂賃賄賅資賈賊賑賒賓賕賚賜賞賠賡賢賣賤賦賧質" +
        "賫賬賭賴賺賻購賽賾贄贅贈贊贋贍贏贐贖贛贜贝贞负贡财责贤败账货质贩贪贫贬购贮贯贰贱贲贳贴贵贶贷贸费贺贻贼贽贾贿赀赁赂赃资赅赆赇赈赉赊赋赌赍赎赏赐赓赔赕赖赘赙赚赛赜赝赞赠赡赢赣赤赦赧赫赭走赳赴赵赶起趁趄超越趋趑趔趕趙趟趣趨趱趲足趴趵趸趺趼趾趿跃" +
        "跄跆跋跌跎跏跑跖跗跚跛距跞跟跣跤跨跪跫跬路跳践跷跸跹跺跻跽踅踉踊踌踏踐踔踝踞踟踢踣踩踪踬踮踯踱踴踵踹踺踽蹀蹁蹂蹄蹇蹈蹉蹊蹋蹌蹑蹒蹕蹙蹣蹦蹩蹬蹭蹯蹰蹲蹴蹶蹺蹼蹿躁躅躇躉躊躋躍躏躐躑躒躓躔躚躜躞躡躥躦躪身躬躯躲躺軀車軋軌軍軎軒軔軛軟軤軫軲軸軹軺" +
        "軻軼軾較輅輇載輊輒輔輕輛輜輝輞輟輥輦輩輪輯輳輸輻輾輿轂轄轅轆轉轍轎轔轟轡轢轤车轧轨轩轫转轭轮软轰轱轲轳轴轵轶轷轸轹轺轻轼载轾轿辁辂较辄辅辆辇辈辉辊辋辍辎辏辐辑输辔辕辖辗辘辙辚辛辜辞辟辣辦辨辩辫辭辮辯辰辱農辶边辽达迁迂迄迅过迈迎运近迓返迕还这" +
        "进远违连迟迢迤迥迦迨迩迪迫迭迮述迳迴迷迸迹追退送适逃逄逅逆选逊逋逍透逐逑递途逕逖逗這通逛逝逞速造逡逢連逦逭逮逯進逵逶逸逻逼逾遁遂遄遇運遍過遏遐遑遒道達違遗遘遛遜遞遠遢遣遥遨適遭遮遲遴遵遷選遺遼遽避邀邁邂邃還邇邈邊邋邏邐邑邓邕邗邙邛邝邡邢那邦" +
        "邪邬邮邯邰邱邳邴邵邶邸邹邺邻邾郁郄郅郇郊郎郏郐郑郓郗郛郜郝郟郡郢郦郧部郫郭郯郴郵郸都郾鄂鄄鄆鄉鄒鄔鄖鄙鄞鄢鄣鄧鄭鄯鄰鄱鄲鄴鄶鄹鄺酃酆酈酉酊酋酌配酎酏酐酒酗酚酝酞酡酢酣酤酥酩酪酬酮酯酰酱酲酴酵酶酷酸酹酽酾酿醅醇醉醋醌醍醐醑醒醖醚醛醜醢醣醪醫醬" +
        "醭醮醯醴醵醺釀釁釃釅采釉释釋里重野量金釒釓釔釕釗釘釙釜針釣釤釧釩釵釷釹釺鈀鈁鈄鈈鈉鈍鈎鈐鈑鈔鈕鈞鈣鈥鈦鈧鈮鈰鈳鈴鈷鈸鈹鈺鈽鈾鈿鉀鉅鉈鉉鉍鉑鉕鉗鉚鉛鉞鉢鉦鉬鉭鉴鉸鉺鉻鉿銀銃銅銎銑銓銖銘銚銜銠銣銥銦銨銩銪銫銬銮銱銷銹銻銼鋁鋃鋅鋇鋈鋌鋏鋒鋝鋟鋣鋤" +
        "鋥鋦鋨鋪鋭鋮鋯鋰鋱鋶鋸鋼錁錆錇錈錐錒錕錘錙錚錛錟錠錢錦錨錫錮錯録錳錶錸錾鍀鍁鍃鍆鍇鍋鍍鍔鍘鍛鍤鍥鍩鍪鍬鍰鍵鍶鍺鍾鎂鎄鎇鎊鎏鎖鎘鎢鎣鎦鎧鎩鎪鎬鎮鎰鎳鎵鎸鎿鏃鏇鏈鏊鏌鏍鏑鏖鏗鏘鏜鏝鏞鏟鏡鏢鏤鏨鏵鏷鏹鐃鐋鐐鐒鐓鐔鐘鐙鐝鐠鐦鐧鐨鐮鐲鐳鐵鐸鐺鐾鐿鑄鑊" +
        "鑌鑒鑔鑠鑣鑥鑫鑭鑰鑲鑷鑹鑼鑽鑾鑿钅钆钇针钉钊钋钌钍钎钏钐钒钓钔钕钗钙钚钛钜钝钞钟钠钡钢钣钤钥钦钧钨钩钪钫钬钭钮钯钰钱钲钳钴钵钶钷钸钹钺钻钼钽钾钿铀铁铂铃铄铅铆铈铉铊铋铌铍铎铐铑铒铕铖铗铘铙铛铜铝铞铟铠铡铢铣铤铥铧铨铩铪铫铬铭铮铯铰铱铲铳铴铵" +
        "银铷铸铹铺铼铽链铿销锁锂锃锄锅锆锇锈锉锊锋锌锍锎锏锐锑锒锓锔锕锖锗锘错锚锛锝锞锟锡锢锣锤锥锦锨锩锪锫锬锭键锯锰锱锲锴锵锶锷锸锹锺锻锼锾锿镀镁镂镄镅镆镇镉镊镌镍镎镏镐镑镒镓镔镖镗镘镙镛镜镝镞镟镡镢镣镤镥镦镧镨镩镪镫镬镭镯镰镱镲镳镶長长門閂閃閆" +
        "閉開閌閎閏閑間閔閘閡閣閤閥閨閩閫閬閭閲閶閹閻閼閽閾閿闃闆闈闊闋闌闐闔闕闖關闞闡闢闥门闩闪闫闭问闯闰闱闲闳间闵闶闷闸闹闺闻闼闽闾阀阁阂阃阄阅阆阈阉阊阋阌阍阎阏阐阑阒阔阕阖阗阙阚阜阝队阡阢阪阮阱防阳阴阵阶阻阼阽阿陀陂附际陆陇陈陉陋陌降限陔陕陘陛" +
        "陝陟陡院陣除陧陨险陪陬陰陲陳陴陵陶陷陸陽隅隆隈隉隊隋隍階随隐隔隕隗隘隙際障隧隨險隰隱隳隴隶隸隹隻隼隽难雀雁雄雅集雇雉雌雍雎雏雒雕雖雙雛雜雠離難雨雩雪雯雲雳零雷雹電雾需霁霄霆震霈霉霍霎霏霓霖霜霞霧霪霭霰露霸霹霽霾靂靄靈青靓靖静靚靛靜非靠靡面靥" +
        "靨革靳靴靶靼鞅鞋鞍鞏鞑鞒鞔鞘鞠鞣鞦鞫鞭鞯鞲鞴鞽韃韆韉韋韌韓韙韜韞韦韧韩韪韫韬韭音韵韶響頁頂頃項順頇須頊頌頎頏預頑頒頓頗領頜頡頤頦頭頰頷頸頻頽顆題額顎顓顔願顙顛類顢顥顧顫顬顯顰顱顳顴页顶顷顸项顺须顼顽顾顿颀颁颂颃预颅领颇颈颉颊颌颍颏颐频颓颔颖" +
        "颗题颚颛颜额颞颟颠颡颢颤颥颦颧風颮颯颱颳颶颼飄飆飈风飑飒飓飕飘飙飚飛飞食飠飢飧飨飩飪飫飭飯飲飴飼飽飾餃餅餉養餌餍餐餑餒餓餘餛餞餡館餮餳餷餼餾餿饃饅饈饉饊饋饌饑饒饔饕饗饜饞饢饣饥饧饨饩饪饫饬饭饮饯饰饱饲饴饵饶饷饺饼饽饿馀馁馄馅馆馇馈馊馋馍馏馐" +
        "馑馒馓馔馕首馗馘香馥馨馬馭馮馱馳馴駁駐駑駒駔駕駘駙駛駝駟駡駢駭駱駿騁騅騍騎騏騖騙騫騭騮騰騶騷騸騾驀驁驂驃驄驅驊驍驏驕驗驚驛驟驢驤驥驪马驭驮驯驰驱驳驴驵驶驷驸驹驺驻驼驽驾驿骀骁骂骄骅骆骇骈骊骋验骏骐骑骒骓骖骗骘骚骛骜骝骞骟骠骡骢骣骤骥骧骨骯骰" +
        "骱骶骷骸骺骼髀髁髂髅髋髌髏髑髒髓體髕髖高髟髡髦髫髭髮髯髹髻鬃鬆鬈鬍鬏鬓鬚鬟鬢鬣鬥鬧鬩鬮鬯鬱鬲鬻鬼魁魂魃魄魅魇魈魉魍魎魏魑魔魘魚魯魴魷鮁鮃鮎鮐鮑鮒鮚鮝鮞鮪鮫鮭鮮鯀鯁鯇鯉鯊鯔鯖鯛鯝鯡鯢鯤鯧鯨鯪鯫鯰鯴鯽鯿鰈鰉鰍鰐鰒鰓鰠鰣鰥鰨鰩鰭鰱鰲鰳鰵鰷鰹鰻鰾鱅" +
        "鱈鱉鱒鱔鱖鱗鱘鱝鱟鱧鱭鱸鱺鱼鱿鲁鲂鲅鲆鲇鲈鲋鲍鲎鲐鲑鲒鲔鲕鲚鲛鲜鲞鲟鲠鲡鲢鲣鲤鲥鲦鲧鲨鲩鲫鲭鲮鲰鲱鲲鲳鲴鲵鲶鲷鲸鲺鲻鲼鲽鳃鳄鳅鳆鳇鳊鳋鳌鳍鳎鳏鳐鳓鳔鳕鳖鳗鳘鳙鳜鳝鳞鳟鳢鳥鳩鳬鳳鳴鳶鴆鴇鴉鴕鴛鴝鴟鴣鴦鴨鴯鴰鴻鴿鵂鵑鵒鵓鵜鵝鵠鵡鵪鵬鵯鵲鶇鶉鶓鶘" +
        "鶚鶥鶩鶯鶴鶻鶼鷀鷂鷄鷓鷗鷙鷚鷥鷦鷯鷲鷳鷸鷹鷺鸌鸕鸚鸛鸝鸞鸟鸠鸡鸢鸣鸥鸦鸨鸩鸪鸫鸬鸭鸯鸱鸲鸳鸵鸶鸷鸸鸹鸺鸽鸾鸿鹁鹂鹃鹄鹅鹆鹇鹈鹉鹊鹋鹌鹎鹏鹑鹕鹗鹘鹚鹛鹜鹞鹣鹤鹦鹧鹨鹩鹪鹫鹬鹭鹰鹱鹳鹵鹹鹺鹼鹽鹾鹿麂麇麈麋麒麓麗麝麟麥麦麩麯麴麵麸麻麽麾黄黉黌黍黎" +
        "黏黑黔默黛黜黝點黟黠黢黥黧黨黩黪黯黲黴黷黹黻黼黽黾黿鼉鼋鼍鼎鼐鼓鼕鼗鼙鼠鼢鼬鼯鼷鼹鼻鼽鼾齄齊齋齏齐齑齒齔齙齜齟齠齡齣齦齪齬齲齷齿龀龃龄龅龆龇龈龉龊龋龌龍龐龔龕龙龚龛龜龟龠"

    private const val PACKED =
        "7H1v3H7q3y4n4y443H1S7R1a3h3n3B2H4K3f1x7C3w1v4H3w1f6x7o4y1p1C713w3v4v3q6o3R7o1n7a3R6K3R2B5Z3h29587K3H7P344P426C2v2v3g1X3w" +
        "7J593h3J7B4I4v4P3H5Z6q6R3o6q687R3v4K0C3S7Q2X7o4S3I7R3x1q7449743I2g7w2y3f4a7K3p4v5R4x3v2w6y3n1A4C6o6o7K4o6o6C1v3C3p2P3g3x" +
        "3n3C6w3w6o3v3J4K213y4S4n2w6q3n191a6y7J417I5S5m7x3y2J7R3q346q4q4x3J2y7H7R3K4Q424f3y7f4B4a7R4p7Z3o724v3v6o3v7Z3S1A1P5I1q6w" +
        "4n3K4K311q1q3S7a1H3S3B7i2J4A2A4A7Q7K4Q3C1f5y2w5J7v733n232a6h7w1Z315a3I2f3h7K4J2p3I6Y3J236K4K3P7i5g6o1y1v7H426J3h3n3C3g2a" +
        "3Y5w7R4g3n1q4K3S0A3g3q3R3i6K7x4Q4Q6J2v1h7Q3I424q7R3g7p636K3p4f4R3S2Y4y0p1J3v633v1C1n1a4P5o1i3I2x4i7J2K3C3y2x3q3S3q5I6o39" +
        "796B3a3I3v7p333K7Z6C7X2n4A3C2w3q3x3C3n0h2f6g4q3w2Z4S6K1y3v4p1a311Z2x1n3R5A3X7v0i3o7R3a4v3K433v6g3p3n4y3g2Q4H6g7Z3h3i4v4q" +
        "3f3q2w4S3v342K7I5w7K3x4n2a3p1n1n6Q3g3Y3p3w6Z7f3R6K5A2x7p0A7S7p7o4v3v3i4n1v2C5p1a0A2S4K7p1x1f3v6S3o6x111v6i4H6o1y1n4y1v3I" +
        "3S1p3H7x3n4i3K3p3v5a1v6p3C3a5i5p5K6x4B3n5w1n3x7n5w1v1v4w1v4S3v3I2y7B6x1y4p6C3y3H3p4v3y6w1f6w1y3p3i6p5w3J4o4y4Q2w2Z4w2Z1y" +
        "3p4v2P0f3P3H1y4o3g1f1f1f6q4n3C7K2n3Q7p4w447K6C6g3A1v3y3P4n2q2g6K1A3x111i2P3K413q413K2C1a1a2Z1C3K2K3x3A4H2X643o133q2H2f1v" +
        "7n3H4q193S4y3p134S192Z3y2f3h3f3I443S2H6g1a1a3q493K7K6K3q1q1v317S5a6C1y3S5R3A3Q4i6K3q6g4A4K3q1A7x5p4n5x6X1y4S2n7S4n4y6g5S" +
        "4K3o4A5a6K3q1f4g1f7S7o1f3v4v2Q2g4Q1J441Z3I4v313y2v421Z4Z1Z2a4a1a2J3P7H1p5K3P4I3n444v7R4a1q424A1X3Q3A4A1n5a5o1A181q1R3q23" +
        "6Q7h6R147C3A7a3H5h7q7X3I3K6p3p4C3p3v3x0C2v6K6K717q4C3C6I4C4i3C7o3C4v7p3I443Q3i7q4H6K7p4H3S4q4n3n3n7i313I7h4v4p414P3R4i1q" +
        "4S2q4h1A3w2h1R3S6y1f2i3J3i3i111v2B2Y3K4J7i2B7C4i4H2q6C1f3H4P3H1C7f4A3I1i2w5w4i6K2R4y31446J01503n6q2n7o4a1J3q4h101n4n4o2v" +
        "2v4p3J3H7p7Q3h4H3X7p4h7Q700C1X3w1i4P540G7K1X0h6K1W7o193v7P586y7f3f1P3H7a495g412X4n4P5y3R31192g334A3i7x4S3i211P216w1v5w4H" +
        "1v347I6B3H595H3J7h3H193o4X4X4o4f197q7H0X2p4p4v1971413X2a4x0X1o2v73114f1K4a412a1B3K5w5f797o190A3H4i6H532P4i3A311R4v1x1B7q" +
        "1W493o0X4H2w3C7Q5a6g4B3i4q3H4x4R4i7Y413y6K79442C4P3f2p3A4v6o017q3a3J2g1q3a734X2I42613A214C3q5C181y6v4i3K2I3f217w7a5o4q63" +
        "394g4p1A5C7n093p4a4q4J492a4n4y3g1n6I79315f2n2Y7S6g2S444i3v4C4S0a495029114x3n4f7P4K1A397v4K3H4g494X4B3o5C490G130Z2v1K0g4i" +
        "4h1I3H2q3g312i6g111R0h3A3w4P50546W1P1v7v5K41392v6g4i3H3R3g3Z4f4H4R6g4H4X0B3v3i791v2P3H3o6P4H11093q5w4a3K0C3i7H4K0X4I2a2n" +
        "121n1v4X2H5w6Q4f443K4g2K316R5C4f6K6w4y4C2S7o7v6x3I3q5C4f3p7K695w4P3R5v7Y3g4K3p4Y4q7n5n2o1q4Q7o2q3v7Y6o6w1S1A2Q7i2R7R7o6o" +
        "3n7R3v1A7Y7o7o2Q2o4o2R4y3a7Y197P1X2J7I183q2y3x3I3H3J1p3n4v1q2n2p4a3C2v2a3n2o6K147S4o3a292n2p2A1n2w1q5I2H0i5R2B1J3C3Y616x" +
        "6x6Q4w1y6Z1X1B7o421i7o1A2p4x0C1q71232Z5h7q1x0X5Y3w4n6C1S3w4I4n195q7S1S712I1a0p3I3I7K2Y3H4i2P2w3n1X3p0C2w2S3q1C5h7n191A4i" +
        "1h1H2n7g7q1i3x1R6w2a7w213w2Z4S4I232Q2w4X7S4n1A2o7o3o3q4Q3I5q3y4S7v1q4v5S3w6w3a3v4P5C1n1C4o3w3H2p1K7v2o4C714g6Z2y6Q4a6x6x" +
        "6K6x144K6o3y3y4v2A4Q7H4Q4i3J3S1a4S442Y4H7a4S197C1i5y7o4B142n2a4P7f7v4v4H2g7I21311A2x316o4q7p3I5a4y4q2Y3i4q3K1n7K3x2i3y4H" +
        "1q490i6o1A3x4q5J5Q5Z3n214h4C6Q4X7y3v4S516q7o1S3K7f3q5i3v5f1J7Q2B4w7R7S1X5H3g125a3H3C5R3J4J3B1P4K4y7Z4n2n7g3v4P3f6h7I3H34" +
        "7n3H7X726g746g3f6o2v6J497Z5w543n4n1K0A5p7Q714o3R3v096g2A7p1h3A4n1K4S3y6o747v3o4p2w7S4S5Y5a7q710h2K4I7y3H511i344h3I4o0a2o" +
        "2g7S6Y3v6I1I7n3w5q5A3w7R4o1X6g4H3o3f1K4q3w0a2o7v5h7w5A4p4v6o3J3A3I2x7q3K3o4n4Q1a3H4i5y1f3K1P5Q4I4Y6o4n5f4Q3o3H4P4I6Q5C6o" +
        "5o5w211Z3Y7R4h0n4y7o4w5K1y3v1n3i1y7p7I1h4I3x4p2C4n4K7i4q4q1v3Z4a7q4f313o6w2n1n4S3K3K7o5K2i4S5a4o7S3p5C32133p7S6g4I5w3a4p" +
        "4B2n6g4o3x1h3q1a4K4o1h4i4v4C3v3v3n7a3n4o1a1h4h4h0B133n3o4y123w7v7g6i7g143i1n1n4H7p3J2f5I3p7Z5i3Q2K3w3P3P2K3C7P4J2w3H4C3p" +
        "094R2Q6J6J4J3w6J3S3S4R3C2o4n7K3J7R4a3J723I3n3I3o343P1x4q0i6o1h111v2B1h7o4i336x1a7K4S5o0q1v2X5h7K4o1y3K423i3v6o0A7S3i4v4q" +
        "1h3q426g6Y2v3w6Y3I3X721x3I193v4g7n4v1v1v3p1S7Z3Z7i3H4y5Y3q6o6w7Q6h4v3H7Y3A3J6h3y3p3P6g6o1y3i7K7I6w6x7R1n2X7X6o1n3n3n3f4o" +
        "3g1v3B3h3S1x7P343g3g3J7J4K114y4q3n1K4K1S4a4n4H4H7Y3y2x2a24296o3K3h1A3K1K4a1a3n4H4I1v3g3y1a7Y3w3A1A5i3y5K7Y7C4Q4x5q5S1A3A" +
        "3y4Q3K4n3w1K1v3g1q2w5o1y4y1q7f4q7i7f3J1x2J3v3y1K3w1Z4S6Q7R2S7v1J2g1q5i1v4R2w4a4w4f1S3C2S2w1K0n4S2v7v2B7R6o6w3p0g2C6i3o5i" +
        "3x7R4a1x4C6p6Q2v7p7o2w3q1x5q2X1q7K3K5y7K1K7K4K4K1v7p4Q4w3I1K3v5I4o4Q5R3y5J7n6C3y3v1q3w1K1i1C1q5I7n3K1X1v6S2q4a3K4a7I4n4w" +
        "2w7q3Z1f1n2w3v7x3K2w7K3C1J7x3v3Q3y1a4q4p7w4Y6K4i4Q3y2Q6Y1A2Y4J3w3w6Y7S4w4S4o7g7X3J1A3C3i4X4n4n1K7K1f3K6p3q2p2C2C3p3K7y5w" +
        "2p3v3v7R7f4v2a1q3K3o5q5h4n4P2a4q4Y2a4x7R0i3y3y3f3C3p7y5S3v241S4S126o4H1a7I3I2v4y7q5I1a4Q3C3S3x1a7K5q4o4K6q4x2x4w4S7y4a32" +
        "4X3K4S5K4q0n2C1y2o1v4H342p0C2y7n2Z3C5h7q7x2p3f4H2K4q7K4Z1a4x3n2X7S7f4q7K5o3K4o3n5p4Z1X3a3y5q3K4y1i3w3g3v7p4C2K7x4P4H3x7Y" +
        "4a0C1q3S3p3w1a3C3o1q1q1C5h7q4x3a4w6B4v3C3h3g3n7S7Q5p1K7K0C7Q0a3C1p7q6y4q2a2a4S3y2Z2a7q3I3q2a4v4q4C5S3p3o2C2y5q1q3K4a2Z0i" +
        "4x6K7a3n7v3y2v7f1a193v6o2w2a3g1q1a4q3v4n3K5p3x7R4q7K3S4q1x2p4C7v0i5i7K5q6p6p5w1a5q5C7n3w5x6p4Y4o3q3S4C7K6q1y3y197S3n4P4S" +
        "6w4K3w7B3C3n3v4C3v3q3H323J2n3I1a1x3v3p3A3p6S3q4K391a4S4S6K4w4B1p3v4q4S4X4h4h3Y312P11136v292w2i3n3I2C5o4h7w5h1q3B6h1n4Q2H" +
        "0C3h3w3K3f3I137K4P312g1h2y3A4R2f2o2f6n3x4S1i2v2H2Y1i1J5B3n713f5p4Q3R1n3X5R5n614R1q2X6n1Z3S2412290i3P392q3f1a5J6x3p7v6o5w" +
        "193A2C4K3A3x1x3o4n312h2n3a4I523I143J3C0q242f713K6o79244A5g1x3h3v3J4X0X5A3C3q417K2x7p4A7R3q2x2p396J114q4f593n1R6f4p3p4q1h" +
        "2x4B6C5o1Z3S0Y3p3Y3A545p4n1n4h6n194i1i2g3x3J2f312Y793I3v6C2q3C392y2X7p3C3P2q3o1X6R1X3K1p3n1q7q3p7Q2Y6g3i4n5g2I317H7w4q0p" +
        "747C3X2X3f394X1A7o7A316p3q3n196h3h3X3p4p1A3S394f1h3n4f1h4C4x5C2w1n113n3x4A321A0q4C4P1Z7g1q2n1X1y4X3X1q6C6h3X5A3I3K2f2o59" +
        "5A3A3q6i7v3H296f3v415g4H4q3p3y3h1p3C196f4R3i19393q2P3q5p4A6P3n244q793p1p7v6Y6R4q3A3H1x3f3w4q3o1n2J1C3S4h3J3A4x315J1q5w19" +
        "3K2C4A1Z4h6R6h2n4P5p496x3p6o7v6x3n3n4C3p6o2n3q3h3I6p5x3H2P2P4f7f1Z1v4y3y1S4i5J1I5p3i3K0g1a3i6p1K3x1p4q1n3h3y4S3f3x1I4P4S" +
        "6p1K7o3X1n4Z1n6o6o1i6i4Q4A3n7C3n3K4R3p3A3p1q4H4n1q4v7Q4H2w3n5g6J2a4o3v5J3Q6g7J3I7Q3K6K1q3i3J3h4o4S11621q4q4I2y7y7o0w3C2n" +
        "4i3v5w4n7K4H4n2o3p4v7y3n5a3A3f4K7S5h5K3x3i4p3g4I4x3q3q4x7q4a4h7C7n4X4o7p3i3w1P7S4a3o2R3x4H3w3v1Z3K6y3q4n427n4X2Y4R5p0q5w" +
        "3y0a3K3q5S1i4n2n7C6K2o4h0a4R4n7i2S2y4H5x4a7H3P7C1y4A4P3g5q3w2K3a4a7K7h6p2w4Q3Q4C3q6x7y3g3H5w6w5S7a5C1p324S3P2R1B3H4h4132" +
        "3o1n314n7S6J4y3Y3n1f3y1S3J4S1y2g6Y7w545h4w1X3A1v1h7h3R223S4v1p3I1i2I7x4v4H3p6o6a5Y1B3H3v4P3h6K3p3w3v4v4f2P2w3J4f34311h2A" +
        "6w1x1Z5h1n3H6p6g3C427i1a2C345w1J323i3p295a3S6h3Y3v4K3H341f3q3K6w1y6Q6K6o4S4n3H6K114i4R3P2h6h7y4A1n1A3X6o3A4w1a2g1v7Y2y0q" +
        "0n3q393K2w4v4o3A3i716g3n1y3H3g441a3x3v494Q2x3I6w2x5Y1v1S3J1x3p2g4f4f5y7Q6I492H4C4P4q3p6w3K5o3I1q1y3v3w3h3I2w1y2w1K3q4n3Y" +
        "6w3H293i1n4o1B7J6w3I3X713P3f1Q3q6A2B791q4q3o3n317w4v493q3R6q6w5o5Y4q3n3I7C3R3i3I2Z3f7w6g2p1a6p7Q3q6I3R4C6w6w3R3n1x4Z3454" +
        "4p4C6w3X6g3K243H1h4C1i3Y3v324Y1f2p1n3P3q3K4Q3x3g3p3v6C3v4o6g3P2w1f4P3v5A7y4H4w3w7v3g2R4S443n7K1p3f6g3g2A3Q3w3I3H4y2B3P3Q" +
        "6R7o2o4I3v3o7o6Y1y1C1a3w3p3w6p325w2Y1n5w2p1a6R6I3K1Q7o6K3P6Q6K3q6w3R7v6o3o6A6o6p6w3q3K4n4n7Q0f7S4H3H3H3n2p444n493q190f4C" +
        "7Q4n3J3y3J1S7R3I7X4a6K1X1Z4J3n5C3Q7v2p1a4v4q4P3o2h7p6q3I3o1n3I7p1q4v7K1n6q1q3n4P0f1q7n413i1q4Z1R7K0f7Q5R5Z1Q7S1J1K1K2I1K" +
        "5g3n5Q4g2p3a4n4P3q4n3x2R6R3n6R3Q4K1H5o5w3K295Z1f4n3n4n4Q1v3v3K7n4a7x0n742C3v741q6K3w7n4Z4Z7x2p2v3H3g3n4a4q344H3K4q4q4q4K" +
        "6R1x3v3I7P2v5K1R7v2a3I1q7q4v3K4o3q7I7C7o4y4p3o1q3H5S125p412a1f5Y4v0i6K6o3v7Y4S5C4S3R2A2A4A4a7g3K3h1P3n7o4C3g3o295K6C4w1n" +
        "435h4K4q5y6w2i193K5I3S6a4q2q5p2a7v7x1y4I6w6Q6C4C293A3v3A4Y7n7w6C3K434J3P3y6C1y3n7Z4w4S2g0B3f6S4o4o4A71342a6g313p3v3f3n3A" +
        "3C4a3K6g4o4R5w4o3C3q1v3A4H4q2R4i6y4Q7Q7S4Z3q315Z2Q5C3v4f4C7x4o4S3n3o2K2f6i6Y6o7Y794q1I6q3q3x4C4Q1q4q727C4o4A391q3H4H4g3I" +
        "6o2x4P5i2g3w4Y4v2K1q7P1q6o7o3a4Y4n3o7n6Y4q7n3p2n5h3v7n6S3K1Q3q5p7Q4p4q4q3R7Q3Q1S311A7C797n4C3C7a1x4q2B7g5h4o2a5Y2n5p2o3n" +
        "4Q4v3q3v4w3h7n2v7n4H2a3q4S1a2w7o3p2C6f1f5w7K2R6K4H4S3o4f4i6w4q5K216S3Q2v3v5C1n3H1I4w4R423J2f2w1K1p3K7q4C5p7w6K6q6o1n2n4S" +
        "3K4q1H6R4R4P5p7Q2i3H6S6i6I7p3g0f5C4q6o6p4o7H5q3K5x6C4S3x3v4q3q7y3v7x7w4f6q7Y293A2n7Y3o3o6S4w6q6g2o3g4o2a4i3P1K4n3o2w7Y4C" +
        "3w3f6i3C1p4H4S2w3q6o5p3h3A6J4I4a1q0i3o1q3H3A6o5w4X6a1n4H5y5w1K3K4g6Q2f6q2Q3A7Y1n3q6C6K7w1Q4C4p6g2S1n6Q4q7w6K4f7w4C6w6a5I" +
        "6q4q7K6o4v4C1q4i436I2n4i147n6o7q4B1f5C1v4X6w3i3h3A3X3q7w3x6Q3X7o3h3H2y1q3K7Z3y4q3S2q2i3x2Y1x3S341p4I6q3K4P4C6q2v6C7w7P4v" +
        "6i3Q7n2h4o4f7C4a2y3q6C4H2v7o2v4v7n4q7S4o4q5q2f1a4o7Q3f3f7q7q6o1q6q4n4w3n7Z7S413w5Y4q4S3i7X4o7w3R1f4Q1n2a4n4H4w4n7w6w3y6f" +
        "4H4Q7K7q0g6C5q4v4H3K7C6o1v6g4f4o7q2y5q7w7S3i3q4a3Q4C4a4p3q1i1i4C6Q6q3I3q3h223v7o0a7a3I4S7A14197A7g4x0B2o2q1p2Y1Q1A7h1Q72" +
        "5g2q5g5R6g5g5S7S3q1R4v3n2C4H3n7R1S4H6I3H1Q3S3n2n2i6C3y1Q4H3p3p4q3g0q3y1x537g3y7p2w5h1I1a2g424Q4a1h3P5w4p3h6w4i1Q424H2a3v" +
        "7S4n7Q1K3q6I427o4n6w1a6K4p5I6C1B5I3v7H3v5x3X3S496A4Q7Z4v3P5f7a4q5g4g7g7g5Y324n427o547S4H3y3v0g3I6g1Q2a4p4C234n5w4C6C1x4i" +
        "234q5I4n6A4o6K7S7w1v3H3h537o1n7o5Y7Z4o4q6w1a1q4J192C29315o4n3n3I446w1x6C3P0B7g4w1n4o4q3g6w6J6g726p4B3P3n3A4R3I2n3I7p3w7p" +
        "6o3o2I223w4o5i7Z427q5h4Q7v7Q6a4C4B7w532w1X7g0a3p3Z3v6I4o6p3v7v6S4w2Q3H0a1K3q3Q4o7q4J3w6w3q7v3q111A4S2g1q6w737y0f6w2w3I1S" +
        "3n0f5w1y3y2K1n1a4q2o4v3p4v7y4Z4R7x1w5w2o7g334n1q5o1q2x443X1K3y4q2o3C3p7a2q6g1p3p3S5R1K6C3I4n44493g1v3H7p3H2p3v3g1A2J4P7I" +
        "5C1v396g193i4q5C6K7w7g113C7K6K3v4v3S2i2I1n293H1p3p2w3P3I314o341y3y7v3y3o7I7x3K4o731i3y2y2J6K3K3A7S416g4q4o2o4a3H1K1S7Z7P" +
        "3a733Q1n4g4v647w7R4q337K6i4i3K7K7n3I3v1n6g3a4i5C101B2n1f6B3f3y3a3I6i5C7p7x4w6g6w6g4o1n0Y1n7K2J6K1q6a1B7x4p393v6K6a1n4p7x" +
        "7p3Q7v2n1n1Z1v411Y1Z3i18394w1X1f3h0Y4i7p4H0Y2A2I3i3n3n3n3i5p7Q3v2o7w7K4A0y3p7o3n4A2X1a1i2o4y3p5w3p3n2o1q6Q5S1v4P5w3I4v1p" +
        "2q1q4x5q5h1n5Y2q2f7I4x3n5o7n3K334q4X5H3H2y3q5g2i7p383v491K2q6a3p723v3v6a4a4X1P2K5S5K3A3Z1R2Y1f6a5i5g3h294h415w5o2f5o2h3v" +
        "5w293g3R1y4q6i2q2w6o3n3p1R3Q5w3I3S3R5g3n4J7J3H4p3R3h3A1p0Z3h4I3H1n4K4o2y1y5341413K2p742H7o3n391q7q4v43332A1J3a3n4Q621n2v" +
        "2C4n320a6K6w2w6K3R3P1X5g1C4w1y4H4C423f4f42397C6g7y5w3K7q3p2w1y1f6S0a4a1X1a7p1p1a3K3p1A3g3A1p1K4C1y2y3p1K2q324v4q3K535p3I" +
        "1y4x6Z392o3A291p3n3p3K5A3y3H3Q1y6o4w3f3f1n3R3v0a5w332y6K6K4o6w1A4K4K6J4C4K3I4n3I3J4Q3R3H3C3P4S3S4o4a3I5I4w2f2i3K3n1h4C3I" +
        "1x3q6S3o4K4C3n4Q3A7S4J3o6J5I1h6w7R7Q6I3o4A4i4H2P1p1x3f3x295h1J5K3P5C3y3o7v3K4Q3J3H3v397I4a4H6w4P6J3w4f4a6p1a3K293g4P3x3v" +
        "7p3K3p1i343H1h1R5S4P3H7x4C4a4C4a7p4C6w4I3f3w4H3w2v3n3n2P3C3Z1p7h3i7g3K2h3i3v3x3q792P292X1i7q79717Q3S3w7R3S2X6w3q3i1i3i3C" +
        "6K4S3q3y3y3v3q2w4x4S3A1n3y3Q3Q7Q1n1R3H113I3i4p4S4i1J3p4v1I3H6K2g4K4Q1q123J1K3A1h312B3n6w1n4p1J3w1x3n3o422v3S2x123C2i1K4X" +
        "3v7o223p4K4h4f7o2a3g1y3n3p1K3n1P3v3y1A3H4q2v3o1p7n3A3C2C6A1n4f6C3q4v3n3S3A4w4q3q2n3Q3C4g2a6h1f4Z1f3q1R6K1K4X3I6o6I1K2w1f" +
        "5C3A3S1Z4S6h6S1q1n1q3p2a1v4w3n4f1B3n6o1S3i6a6o3g3I2C6a6w3n1n1q6I6A7S5J1I6a4n3J4p116K2C3P3n2i6K4H4S3H7K3f4q6w6w3q3v3a6o3y" +
        "3v4p6g4g4R4Q3n3I1f2w3h3f5I4p4q3f2v6w3y5C2o6K1I2i5K4H4K3f3K3i7H4w7P4A7o6q7p7o545h4P3o2H413J3I4n7o4S4x4B3p3J6a4K4Q4C4n4i1q" +
        "4Q1a3S3v3R1q4w3A2S3h6C4q1Z6w4S2x4H3y3I3I3q1x4f1x2I2i4X3v3v1B3g3p3I4q4i7Y3y7p1v7x1v3a6o6h3J3q3C6w5o1p3H3p4X6K4S4y4v3n2C3H" +
        "1q1K5o7o4H1n4p5p7Z1f5h6q1q2I3K7w3q7K3a3i3n4S3n1h6S4q2f4y6I493y6Y3q5q3I6J2h5I3x3H4o4f5g7g3v3H4q6g6i4a4w4a4K3p3v4o3h3h7K3K" +
        "1n3p3p4A2y4S6Y3o7v3Y4n3p1i6p4H3f7P4w3i4n4A7H3I7o2y3K6q7Z7o3o2H411v543y6o4n3J7o4x5h4P4q1q4C4Q6q3R4n4K3H3v3i1q4Q3S4i7K3v1a" +
        "1x6w3A2S6i4w4a1Z4q3y6C3I3h2x1x4f3q4i4Y2f3K2I3K4S6w4S3J4X3C4y1p4w7Y5o4i1v3g6h3p3v3q7p6K3a3H2C4v3n5p6p2I5h3H4a4H1B1q1q1f3a" +
        "4p1K6J1n5o7o3q4S6S3p4y1h3o6I7K3n1n2f5q6Y7v495g4f4A6g4q3v3v3p3f4o3h3p4h1v3H7v3y447v2o1q7x7x4p6A4Q421R1v147p3i3a3K426p4R2I" +
        "146I3v3H6A2I3H7w3v5Z1f6w1H4f3x4q3o3x497K3A2v4H6Y1v3q7R7K7v3K7K7K6w4I4w4H3i1I3a4Z3S3p2n4A4q0g7K3i4n7K7i6h2h5i3B3I1A0A435a" +
        "6Z3J1v3i4i7o144K496i2v0h6g5i3x2x6g6i5C0B1v7A114x3J1n1x5C1n6w6g6w3I5w196o4y2q3S7o2a3v6o3v0g4v4x2a5C3I5w2v6w7S7S4S7K4S4K3i" +
        "6i6a3H4v4i3h1S1v6w1n7C3w1R3H4P4Y3n4w3n540v2p1v7S7g3p4a3x2a4q3x3y4A4q7a3i1p1a112X2y3n3C2X334y3H1f4Q7q4P6w1y236Q3y5R7n247I" +
        "1v4Z191y3f4v0q2o5w3H4A3a5a3J2a443y3I5h5J5w6o1A7p3h3y3B2f2Q295i6A6p3y2I2p3v647C7n4q4R4R4Y3v7q1y3v1y3i6A4v5h4S5p3x0C4X7f3q" +
        "7Q3w4S4q5K5p742w2Z1x6J1A1A1f1q1f4P2w5A4H3f3K6q2w5K3a4q7v4n1p2a5w2o6o1K7v7K6p4f1R3I1q646Q3y6o3o3v6o3K5C3i3K3K2Y3n3i7Q7h7Q" +
        "3v4K3i7Q7R4y3R3i4A4B4K4P2p3p4q7R3f3o4n7J1p4w4x1n1J3v3q3v3Q3A1C1A4o1B3o6Q4H4f2x7Z5x4h4f3v3g3v7J3q5w6Q1p6w3n3n4C7q4Q7q3h7K" +
        "3f0a5Z3A7o5J3n7S4g4v1S3J5w4v1J4Q7Q3H4n3q2f3C6Q3H3o3I7o4n116a4n3K414v7S3J7o3o3Q724a3v1q7Z6K0C4q3w3v3S4P7q6p6w2Y4g5g3x295S" +
        "1f1h5o7J3S2B6C2R5w4n1p7v3P1n2w4Q3A5i4q315g5g123I5C3I3v6w5h7w3w5w7q1q3q3p3I5w323f4Q3P3v4Y7n326w6Q3v2w4o3o3v4y3h6p3q2I4v6K" +
        "326g1K3g4a3K1y6w4n4w6C7w4o3q4n7n5Z4w3i7i4A1I1I2Q4X6K2Q6K3v413R5Y3v4n2w7g1p7h324q6y4Q0A5C2p6Y6o4I794n4C7g7w7v3o5x7p3v3n1P" +
        "3Q3n4A4Q3v4v3a122S194q4H2Q1y421P6w4X7n3P4P3I3a2g1K3w6Y3H5w2w7Z1q4o5a296A7w7w7w4f447q4n797Q0C6C1h7C4v4w3S4v6q1A312Q1x3i7Z" +
        "214Q3y317i3v7X2Y2w4n4J3K1K3p2Z2a3x6g2a3o4I6y5w4q1y2Z2Q4C3v3n3I3v0n4f4n3n4S6w7x1a4H6S491a1K6o3K6I7S7w5C2w6o3v4J6h1K4O4S5C" +
        "5q1A3C7a6g3S3a3x5h5n4P7n3w1f6p6q2i0Z1K3o193p4q3f6Z3g5Z7o7g3Q6g4a3a2a6Y3I1y7Q4f3I7q7S7y3I4w6Z1g4f7X4a3K4v7K3v3w4H1K4n4C3q" +
        "4v444n4h4R4n6Q2Y3K1h3I6o3q3w5h4p0h6I7K2w7i4n4h7q6K0Z6q3h4C3I4w6Q4P2w5A5C4p6p6w3Q4o6o3q6I5I6A4P4R6R5C6K3o3S4P6R7Q4i1A2X3w" +
        "3g3J4H4w4Z5w1C4X413a4I7J537o6a1y2I7p3o724g1v3h3C4Q3H1h4p3f7g347i4A4n6o3S1P3P4A6w1R1q6K3v3P5g4Y7w713P2C3f423w3K5o3A325g4H" +
        "3I4f7x320A4R4v4q4q397Q4Q6I3Q7o2w2a793H4X5K3I645x3v4H2g7S2I7K3o3v1B7n7w193o29497Q4I4w1n4Q7g3g5g414S2Y4Q1A795p6g3n6w6w2w4H" +
        "4H7o4f6w3o2w535w7w5p4K3g3H0g2w2f6A6g3v4a3A52195g4H5p3v5x4y2w2o3J4w4a3o4q5g3w3v4C7J3o6B7w3a6o3I6w5x6Q4C5C646I3p6K1R3n3o1S" +
        "5o3Q4K5K4q3y5C4w7p4S4o39727a3v4w3Q7H7H1R1h334n3q1p4X543v3J6q3n3n7o5a0h5h311a2g2p4i742q5i1a4I4Q31193Y6C3v1v2q5h3w6J2g7K7S" +
        "3g3o1R3v416o6p2S3p1K191h4u2Y6B1B2K3K3P144S1p4C1f1h1a3R6J116S2a3J3n2S6o6o3B6J4C3x4v3p0h6p1K3n1v6o6Q741Z3q4I2q4H7i2o4S3q1X" +
        "5K4K3n4I7Q3n3K1i3q3S3I6p1I1n3q1n1X5K4K3n6p3I3K4I1I7Q1i3q3S3h3I1P3H4v3B1v3S4S3K4Q4v3K3S7o7o1y4S4v3K4q4y2h3A4q4q3K3K0A744y" +
        "3I5C4x4C4R4S493p3J1R1J6K3S347I3i2w3P3R3I4R4o7K4K4H341i1Z3o3A441X4w4n3n4X146Z3P2v217S2w6q2w0Y4K1q7i3i7R3w3C7P7S1i4y4a494Y" +
        "2C4a4Z7K1i3p3n2o7Z3x3y3f3A6y6q4p7Q1A2p4q0C1K4A3q7S3H4a0n3o4x3P7q4n5C5g7C7a2w3f3p4x5I5K4K4S1y3n4K3x4C7g3y5A3A5i3p0f3p5o3y" +
        "3I3H3q4I3i2o2R3x3n2K7K7K3p4S7S1Q1q3g3o6y6o3q1x7q7o3K1y4S6q3H3A4y2h6y4q3K4q7K4q3K3x4a0f3S745C4R0A6q4y4x4C4x3I3y1R492w3R4I" +
        "344S3p1J3f3I3P3i7K7I2v6Z4K144H3A4X3w3P4n441q1i3o1Z4o7K3y1X4w344q4R3C7P7R3i7S1i7i4a2w494y0Y3x3P3f5C1Q3A4Z2C7Z7Q4Y4p1i3p6y" +
        "3n4a2o7K5g3o1A4x3q4A4K7C7a0C7S4n3o3H0n7q1K5I2p5A1x4S4C7g1y4K3n5K3p5q3A3p5i2o3q3g6o2R3I7q3p3n3q1R491i3v3J3J7n4v4J2o4y4q4g" +
        "7S1n3K1i3Y1f4f4g5C5i2I5C1a3n4S3Y1y2o4C4q2n1q3A3S4K3H0C1a1p5Z1a2y4a297I5i4C1K6S6q4a1X3H333Y3q491n3g6a3K4x2Y1v4o5a3q4S2q3K" +
        "3H3y1R6a3q4S1i4a3A3K3a3y3q7q4q7w3q4Q1q3v1a3n4S1y3Y3A4o1a3y4C3K4q2n2o1p1i3S1q0C3q1n4K291a2y1a5i4a4C7I3Y3K334a3H6q6S3v3H1X" +
        "3q3g3q6a494S1R3H4Q4x3K1v2Y1p6a3a4S3q4a3A7q3q3y4q7w1q3K4C5p4C3B3h3f4S3i1p3J3q3P3f7K3P3H6C1p3i2y3S3P3p3p3Q211i1p4P3p3J217K" +
        "3y2Y12192A312h3I4P4n1B3S6K1n4p3f241a3w2Z6S2i3q3f1K4n1C3H3K4I6y7x3g243q394Y3S3I2H1A3Z3v3K1p3I1A7x3x3a3q3R1A2o6g2I3p1h393H" +
        "243v5C2o1K3S2o1y1A1v3y4o3Q1n3S3I3f2R3n3i3Q3Q1p3g3H7K6q6C3I6K3K3o4n3n4C5C3n3A6q4n1v3P1B2x3P39741Z3n7a4n6q0C6p4P3p1P3g3J7g" +
        "297K4K3i6S3o3a3K3A4R3v6y3H4X7x3C1p5p1a6o3I3i4P4Q3p7Q1R427o6S3p3A3i6o4v2a6K6Q39741Z4n6q3p0C6o6p4v1P296Q3g3J7K4P3p6K7g3v4K" +
        "3a3K3i3o6S3i3A4R6y5p1a4X1p7x3C3H3i4Q3I4P2a7o423p6S3A6o4n1P3I2K641q1q1q1q3I1q1q3o6R5w3C1n6g123n7P3K4q1C5a7w7q3q744p7R4Y3C" +
        "3q7p7Y6o3I2g7I3x311a0B1I2C1A3A4S3y4Y5I1y3K3X2a4y4K2g2w4i5K4p4q1P4f2i3Q3g1K2Q3y2K1i3C2v1y4K3x4S3i3n4w6o6J4q1Z6S3q2Y7X7K6A" +
        "1H7Q1q4a3o7S7q1q1C0C424w3g1i127Y7I1i6i4q1K7p203p7g0g4K3f393I6o3n3n4p7I6g3S1K7f5a4C4a4Y0B5h1n616A6J7K1y7v4o5w3w2y4v4w541v" +
        "4A7P7g4o2Y3f2H1x4i1a1J3f7C6o3P7S3C3K4o3f6w322a3y7q4H4Q1i4h323q7x6K7o1S2I192o3n7g1n1f7p0C3q7q4v3f7P7o1J7o7n3v1y3y4q6o2A1n" +
        "7C2a3f2y6w4v6K7h1v3g3A2a3i7J1n3h4S4n7q2a2A3C4n1P4P5x6i3g2w3J4n3y3w2Q3i5Y2S4n6a7q4H5y2X3o3a3S2n2I4Q4R4x7q5I3o3h4Z2w6g7H3y" +
        "1Q3i4H6J3S4n5y4q4X7q3Z7i4K4K6J3y7B6y3n3n127J6h3f1v2C4R3n1i4q3q4p3X2R5J3n134v1h1S541q1f3o1p3f5h3n1a4B2a2y5J4K296w1R1A2H7S" +
        "1P7g2o333S4H4q1K1A293o5h3n7K193v5S2p3q3h0C1C317o3y2w3w4p3o3P5w7g4o6h6Q7H7n0p1f7h4C2i6o1i4f4i2I3C6J6w4n1a7S1y324v6C3p7A3Q" +
        "3y3P0A2S6a3w1i6J2C6h3S1v1B3v2g3p3X012n3Y3H3v1n2o1y3o3p5g4H1S3C6S5x1h6Y3q1A4n495o2Z191S0C321q313C2p5g3f4o3q4v3B3v5Z0X5Y1y" +
        "6g4B6K7P7w6g2Z414f4i3q7K5C313n523Q4q6q0i5C6A1I0g2v3v2v5q7v3p3y1f6i3q422Q3x5g2v6g6g1a4o3v1y3I2R2X3n4a6o3A6Y2B1A1v1a7K3S4C" +
        "1n3q334C1f6R4n6q7i4v5C3n6A3n6o3g3n127J3n1v3f296h2R3n3q4n4o1i5o5J3X1a1S2a3S1q3f3v541a1v1p3o7i3n3n7P1f2y4v4B1h5h137S3o3v3o" +
        "1R19292B1P1A7K3n5S2p331q7g2B1A6w4C3n5h4K4q211K5I2I1A2i6h0B7h3w327A5g1v2w6J1i7n2Z323P4J2x1f423o41411i1C5w3v4C3h7H3p3y2v0p" +
        "7o6Q3S6g2S6Y2C6q2v4f4B6J3y3Q191i0A4i3C6C4v4n6h2X3p6a2H6w3p3P013v3B5C3C5g1n1A2C2n4H1S6A3Y3X3p4n3p492Y2o1y3q3S5x3H3C2Z3v4H" +
        "0C313f3v1q4f4o0X1S5Z6i4a5Y5C3q1A5C3n5C526g1h1y7K311n1f2v5q6A7v3y1H3Q4q3o3I6i2Q6R1n6o2R3n3v1y4C6Y3A6o7K331f4v3x3x5o4n4p7o" +
        "1K2X2y4w6q4o3n5p320a1A1A421X5p2p6y6I7K3v7n7o0C4n7S7o3S1p7Y2C3K6o2o4A3K3x1n2q3p2K245o4n4p7o1K7q3x6q7Y4o4w3n5p2y5q325i1X7o" +
        "245p6I421A4A2p3f7K6y7S7n3v4K7o4n7o7n3p6o3S2C3K4A2o3H2q4S4S1a3n7S1p6p3x4w7w7n3q393R3C1q012A2I4S3K6S6x3o4w6i5C3y4q1X4p4w1K" +
        "4p3K1h7q3q3Q5C7p4p2Y3f7n3Y3o2I6w2g4q6S7w7Q6w7X5C1a4Y4w394Y7p1A7p7Z0a4K3K3y4a4Y4p4I7p4X6x6K6K3X3H4p3q5o3K7q4w733I1S3K3I7v" +
        "3P3Q6C1f4X4v3Q323g6I5o7R7Q4J7o7o6K6w6Y1g1q7S4P3K4f2w3q2a5Y4C444X5I6o4v427S7o0Z4q6S142H3K5Y6K0Z6w3v3y3y3y3y1q3y4X2i5I5q7C" +
        "7C1A3q4H13127v4A0n1x123g5o3i3P6g3f3P1n3n1f1a3g123n3n7Y6q4o7Z2f7q7Y6q4o7Z7q2f3h7n7q4g4x7C1x3x4y4q4n4P4P4y3I4w7S7o1n1q296x" +
        "4A4A7I4Y2g324q3x2o2Y292I0A0C3n7o7q4x1n6a5o4i1S3q6Q4p2o6Q5C3o7C1x3x4n4y4q4P4P7o1S1q3I1n4y4w7S6Q6x293x3A324A7x297I2o2Y4q7x" +
        "292I0C3n7o0A5C5n1n4x4i3q6Q2o3o4v1f442Y113S4f2f1f1f4v1f443S4f2f1f1f4X4X4I4I3H4n4x2o6q7S3K4q7p7I4K1h4K3h1x4x7x0B7q3n195Z0C" +
        "7Q4o3q4q1p2C2w314K6i4f5A5o4f3p4p2a3q3H6g7v2f4x7q3o5w4I3H4w2o4K6q7S3K4q7p3q4K1h4K7I0B6g4x3h1x190C7Q5Z4o4q1p312a4f3o5A6g4f" +
        "3p5o4p3q5w4h2Y1A4v4S4n537S4w2A3I4o1A3S5Q3P3x342Y4S4J2A4K542o4a6C3q3x3X2C3I3I7S2q3n3K6g2w3f4f4q6A5C0i3n1f3v3P424f3q3f7q3v" +
        "7K3i6I4v3K6I537S2A4q3I3P1A6I3x4J4K4S3P3f3S2A5Q347K1a4f543f426C4a2o6I3x7q3q3I3I2C3X3n2q3K4f7S0i6g3n4q1f6A3v3p3i3K4v1R0v2g" +
        "3C1J2P4Y4g1A1K29346g2n1q6g1Q3v4Z2J1q2n1f1f2n5g2g3H446o4f3K3v4v3o4Q3f1q4P4o1q6C1i5i4K3f3y7S1A7S1Z2Y4o122C5a7p4f6x7x6x7a3H" +
        "5A7p7Q6R4w7g192w5o2Y1i4S3A4x0A7Z3f1X4n1p1x4q6J413H3v1f1S4a5I2n3v3v6w3f5o4H3Y1n1A4w3f0C4S4X4f4I1n237g3I6o0g6C5p2g3n5o1i7v" +
        "4J193n4q1a6o4o4q4i6J3K6Q6I7Q7g6R4w142w5o6Q4S1i4i2Y1X3A7Z0A3K3f4n4x4o1x6I6o3n6J4I2g1p414q3K3v6w3f4X2n3v1S5I5o1f3v4H3H4q1A" +
        "4X0C3f4S4w1n4f0g3I231n7g6C1i4J195o5p7v1a4q6o3n6J5h3f4R4y5w7n3q1h712A7n3Q3H1P7v710A114w194f3n7S1A2I0A4Q7R0n2w1X3K1v3o5g4Q" +
        "0C5Y7S7v4C1Q3n3I7i3H3C0f3K6i4H3f6g3i4o7S7v6S4S6Q7v1q6I6o5h3f3H7n5w0f711h3q1P1v6Q717v3H3Q7n2A4H3K0A114f196o4w1A6I3n1R0A7S" +
        "4o2I7R3K5g0n1X2w3o4Q0C1R3I5Y7S7i3n4C7v3C6i6g3f3i7S6S7v4S1q6R4o3A3p7o3A6S3J3n3R5I3I6S6K4C6o5a5a4P3P3P5q4P525A4X4w4w4w4R6I" +
        "5o4X3o5C1a3S7h1p7H423P3w6I1x1Q3p0q3p5Y1Q3J4Q4R5p5p7o2A7o2A1x5a1R1v2g2I4R4o7i7Q4H7p1I3g4n313I3X3H3I3H3J3q1f3Y3R2g6w3P2p3C" +
        "7R3R7C3J3q3R6w1f2g3H7o7R3C3R7C6w2w1v2n6w1v2n1X1X7K"

    private val index: Map<Char, Int> by lazy {
        HashMap<Char, Int>(11953).apply {
            for (i in KANJI.indices) put(KANJI[i], i)
        }
    }

    fun contains(ch: Char): Boolean = index.containsKey(ch)

    /** 声母类 0..7；表外按零声母处理。 */
    fun initialClass(ch: Char): Int = valueOf(ch)?.let { (it shr 6) and 0b111 } ?: 0

    /** 韵母类 0..7；表外按「中」处理。 */
    fun finalClass(ch: Char): Int = valueOf(ch)?.let { (it shr 3) and 0b111 } ?: 1

    /** 声调 0..4（0 = 轻声 / 未知）。 */
    fun tone(ch: Char): Int = valueOf(ch)?.let { it and 0b111 } ?: 0

    private fun valueOf(ch: Char): Int? {
        val i = index[ch] ?: return null
        val hi = ALPHABET.indexOf(PACKED[i * 2])
        val lo = ALPHABET.indexOf(PACKED[i * 2 + 1])
        if (hi < 0 || lo < 0) return null
        return (hi shl 6) or lo
    }
}
