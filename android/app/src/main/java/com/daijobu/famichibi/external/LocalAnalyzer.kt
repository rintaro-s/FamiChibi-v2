package com.daijobu.famichibi.external

/**
 * On-device version of the server's analyzer (server/app/analyzer.py) for messages that must not leave
 * the phone (LINE / SMS notifications): picks the chibi's expression, motion and effect, whether it is
 * urgent, and quick replies — with the reason, like the server does.
 */
object LocalAnalyzer {
    data class Result(val emotion: String, val motion: String, val fx: String?, val urgent: Boolean, val replies: List<String>, val reasons: List<String>)

    private val EMOTIONS = listOf(
        Triple(listOf("誕生日", "おめでと", "🎂", "🎉", "🥳", "合格", "やった", "最高", "優勝", "勝った"), "excited", 3.0),
        Triple(listOf("ありがと", "感謝", "助かった", "助かる", "サンキュ", "thx", "thanks", "🙏"), "thanks", 3.0),
        Triple(listOf("大好き", "愛して", "好きだよ", "❤", "💕", "💗", "😘", "会いたい", "ぎゅー"), "love", 3.0),
        Triple(listOf("嬉しい", "うれしい", "楽しい", "たのしい", "美味し", "おいし", "いいね", "よかった", "良かった", "わーい", "笑", "😊", "😄", "😆", "ｗ", "www", "✨", "♪"), "happy", 2.0),
        Triple(listOf("食べてね", "作っておいた", "作ったよ", "置いてある", "置いといた", "おやつ"), "love", 1.5),
        Triple(listOf("悲しい", "かなしい", "寂しい", "さみしい", "つらい", "辛い", "泣", "😢", "😭", "残念", "ごめん", "すまん", "申し訳"), "sad", 2.0),
        Triple(listOf("怒", "ムカ", "むかつ", "いい加減", "何回言", "片付けて", "片づけて", "早くしろ", "💢", "😠", "😡"), "angry", 2.0),
        Triple(listOf("えっ", "え！", "まじ", "マジ", "うそ", "びっくり", "驚", "😲", "😱", "!?", "！？"), "surprised", 2.0),
        Triple(listOf("大丈夫", "心配", "気をつけ", "気を付け", "無理しない", "体調", "熱", "風邪", "雨", "☔"), "worried", 1.5),
        Triple(listOf("眠", "ねむ", "おやすみ", "寝る", "寝ます", "💤", "😴"), "sleepy", 2.0),
        Triple(listOf("お疲れ", "おつかれ", "ただいま", "おかえり", "了解", "りょうかい", "OK", "おけ", "はーい", "わかった"), "happy", 1.0),
    )
    private val MOTION = mapOf("excited" to "cheer", "thanks" to "bow", "love" to "bothWave", "happy" to "wave", "sad" to "sad",
        "angry" to "angry", "surprised" to "surprised", "worried" to "think", "sleepy" to "sleepy", "neutral" to "talk")
    private val FX = mapOf("excited" to "confetti", "thanks" to "hearts", "love" to "hearts", "happy" to "sparkle", "sad" to "rain",
        "angry" to "anger", "surprised" to "exclaim", "worried" to "sweat", "sleepy" to "zzz")
    private val JP = mapOf("excited" to "大喜び", "thanks" to "感謝", "love" to "愛情", "happy" to "うれしい", "sad" to "かなしい",
        "angry" to "おこ", "surprised" to "びっくり", "worried" to "心配", "sleepy" to "ねむい")
    private val URGENT = listOf("迎えに来て", "迎え来て", "むかえに来て", "迎えにきて", "至急", "緊急", "急いで", "いそいで", "今すぐ", "すぐ来て",
        "すぐきて", "助けて", "たすけて", "事故", "救急", "病院", "財布忘れ", "鍵忘れ", "カギ忘れ", "電話して", "電話ちょうだい", "SOS")
    private val REQUEST = listOf("して", "来て", "きて", "ください", "ちょうだい", "お願い", "おねがい", "買って", "入れといて", "やっといて", "しといて")

    private fun find(text: String, words: List<String>) = words.firstOrNull { text.contains(it, ignoreCase = true) }

    fun analyze(text: String): Result {
        val reasons = mutableListOf<String>()
        val scores = HashMap<String, Double>()
        val hits = HashMap<String, String>()
        for ((words, emo, w) in EMOTIONS) find(text, words)?.let { scores[emo] = (scores[emo] ?: 0.0) + w; hits.putIfAbsent(emo, it) }
        val emotion = scores.maxByOrNull { it.value }?.key ?: "neutral"
        if (emotion != "neutral") reasons += "「${hits[emotion]}」→ 表情: ${JP[emotion]}"
        var motion = MOTION[emotion] ?: "talk"
        val urgentWord = find(text, URGENT)
        val replies = mutableListOf<String>()
        if (urgentWord != null) {
            if (emotion == "neutral" || emotion == "worried") motion = "knock"
            reasons += "「$urgentWord」→ 急ぎの連絡と判断"
            replies += listOf("今行く！", "10分待って", "電話する")
        } else if (find(text, REQUEST) != null) {
            replies += listOf("了解！", "あとでやるね")
        }
        when (emotion) {
            "thanks" -> replies += "どういたしまして☺"
            "love" -> replies += "ありがとう❤"
            "sad", "worried" -> replies += "大丈夫？"
            "excited" -> replies += "おめでとう！"
        }
        return Result(emotion, motion, FX[emotion], urgentWord != null, replies, reasons)
    }
}
