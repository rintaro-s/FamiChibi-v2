"""Message analysis for chibi delivery.

Decides, for every family message, how the sender's chibi should deliver it:
  emotion  -> facial expression        (happy / love / sad / angry / surprised / worried / thanks / sleepy / excited / neutral)
  motion   -> body motion              (wave, dance, bow, ...; see chibi-stage/src/motions.js)
  urgency  -> low / normal / high      (high = break through quiet hours, lock screen, re-notify)
  replies  -> suggested quick replies
  reasons  -> human readable explanation of *why* (shown in the UI: "なぜ？")

It is deliberately rule based: deterministic, instant, works offline, and explainable. The judges'
feedback asked how the AI decides and whether it is trustworthy -- every decision here carries the
keyword that triggered it.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

# (keywords, emotion, weight)
EMOTION_RULES: list[tuple[tuple[str, ...], str, float]] = [
    (("誕生日", "おめでと", "🎂", "🎉", "🥳", "合格", "やった", "最高", "優勝", "勝った"), "excited", 3),
    (("ありがと", "感謝", "助かった", "助かる", "サンキュ", "thx", "thanks", "🙏"), "thanks", 3),
    (("大好き", "愛して", "好きだよ", "❤", "💕", "💗", "😘", "会いたい", "ぎゅー"), "love", 3),
    (("嬉しい", "うれしい", "楽しい", "たのしい", "美味し", "おいし", "いいね", "よかった", "良かった", "わーい", "笑", "😊", "😄", "😆", "(笑)", "ｗ", "www", "✨", "♪"), "happy", 2),
    (("食べてね", "作っておいた", "作ったよ", "置いてある", "置いといた", "おやつ"), "love", 1.5),
    (("悲しい", "かなしい", "寂しい", "さみしい", "つらい", "辛い", "泣", "😢", "😭", "残念", "ざんねん", "ごめん", "すまん", "申し訳"), "sad", 2),
    (("怒", "ムカ", "むかつ", "いい加減", "何回言", "なんかい言", "片付けて", "片づけて", "早くしろ", "こら", "💢", "😠", "😡"), "angry", 2),
    (("えっ", "え！", "まじ", "マジ", "うそ", "嘘", "びっくり", "驚", "😲", "😱", "!?", "！？"), "surprised", 2),
    (("大丈夫", "心配", "気をつけ", "気を付け", "無理しない", "無事", "体調", "熱", "風邪", "雨", "☔"), "worried", 1.5),
    (("眠", "ねむ", "おやすみ", "寝る", "寝ます", "💤", "😴"), "sleepy", 2),
    (("お疲れ", "おつかれ", "ただいま", "おかえり", "了解", "りょうかい", "OK", "ok", "おけ", "はーい", "わかった"), "happy", 1),
]

MOTION_BY_EMOTION = {
    "excited": "cheer",
    "thanks": "bow",
    "love": "bothWave",
    "happy": "wave",
    "sad": "sad",
    "angry": "angry",
    "surprised": "surprised",
    "worried": "think",
    "sleepy": "sleepy",
    "neutral": "talk",
}

FX_BY_EMOTION = {
    "excited": "confetti", "thanks": "hearts", "love": "hearts", "happy": "sparkle", "sad": "rain",
    "angry": "anger", "surprised": "exclaim", "worried": "sweat", "sleepy": "zzz",
}

URGENT_WORDS = ("迎えに来て", "迎え来て", "むかえに来て", "迎えにきて", "至急", "緊急", "急いで", "いそいで", "今すぐ", "すぐ来て",
                "すぐきて", "助けて", "たすけて", "事故", "救急", "病院", "財布忘れ", "鍵忘れ", "カギ忘れ", "電話して", "電話ちょうだい", "SOS", "sos")
REQUEST_WORDS = ("して", "来て", "きて", "ください", "頂戴", "ちょうだい", "お願い", "おねがい", "買って", "入れといて", "入れて", "やっといて", "しといて")
DANCE_WORDS = ("踊", "ダンス", "パーティ", "🎶", "🎵", "♪♪")


@dataclass
class Analysis:
    emotion: str = "neutral"
    motion: str = "talk"
    fx: str | None = None
    urgency: str = "normal"
    replies: list[str] = field(default_factory=list)
    reasons: list[str] = field(default_factory=list)
    party: bool = False

    def to_dict(self) -> dict:
        return {
            "emotion": self.emotion, "motion": self.motion, "fx": self.fx, "urgency": self.urgency,
            "replies": self.replies, "reasons": self.reasons, "party": self.party,
        }


def _find(text: str, words: tuple[str, ...]) -> str | None:
    low = text.lower()
    for w in words:
        if w.lower() in low:
            return w
    return None


def analyze(text: str, kind: str = "text") -> Analysis:
    t = text or ""
    a = Analysis()

    # Emotion: score each rule, pick the best.
    scores: dict[str, float] = {}
    hits: dict[str, str] = {}
    for words, emo, w in EMOTION_RULES:
        hit = _find(t, words)
        if hit:
            scores[emo] = scores.get(emo, 0) + w
            hits.setdefault(emo, hit)
    exclaims = t.count("!") + t.count("！")
    if exclaims >= 2 and not scores:
        scores["excited"] = 1
        hits["excited"] = "！の多さ"
    if scores:
        a.emotion = max(scores, key=scores.get)
        a.reasons.append(f"「{hits[a.emotion]}」→ 表情: {JP_EMOTION[a.emotion]}")
    a.motion = MOTION_BY_EMOTION[a.emotion]
    a.fx = FX_BY_EMOTION.get(a.emotion)

    dance = _find(t, DANCE_WORDS)
    if dance:
        a.motion = "dance"
        a.reasons.append(f"「{dance}」→ 踊って伝える")
    if a.emotion == "excited" and _find(t, ("誕生日", "🎂", "おめでと")):
        a.party = True
        a.reasons.append("お祝いの言葉 → 家族みんなのちびっこでお祝い")

    # Urgency
    urgent = _find(t, URGENT_WORDS)
    if urgent:
        a.urgency = "high"
        a.motion = "knock" if a.emotion in ("neutral", "worried") else a.motion
        a.reasons.append(f"「{urgent}」→ 急ぎの連絡と判断（おやすみ中でも通知・既読になるまで再通知）")
    elif kind == "voice":
        a.reasons.append("ボイスメッセージ → 声をそのまま再生")
    elif len(t) <= 6 and a.emotion == "neutral" and not t.endswith(("?", "？")):
        a.urgency = "low"
        a.reasons.append("短い返事 → 控えめに通知")

    a.replies = suggest_replies(t, a)
    return a


JP_EMOTION = {
    "excited": "大喜び", "thanks": "感謝", "love": "愛情", "happy": "うれしい", "sad": "かなしい", "angry": "おこ",
    "surprised": "びっくり", "worried": "心配", "sleepy": "ねむい", "neutral": "ふつう",
}


def suggest_replies(text: str, a: Analysis) -> list[str]:
    t = text or ""
    if _find(t, ("迎えに来て", "迎え来て", "迎えにきて", "むかえに来て")):
        return ["今行く！", "10分待って", "ごめん無理"]
    if a.urgency == "high":
        if _find(t, ("鍵", "カギ", "かぎ", "財布", "忘れ")):
            return ["今持って行く！", "何時に帰る？", "電話するね"]
        return ["今行く！", "電話するね", "大丈夫？"]
    if _find(t, ("何時", "なんじ", "いつ帰", "いつかえ")):
        return ["もうすぐ帰る", "19時ごろ", "遅くなる"]
    if _find(t, ("ご飯", "ごはん", "夕飯", "晩ご飯", "弁当")) and t.rstrip().endswith(("?", "？", "る", "か")):
        return ["いる！", "いらない", "遅くなる"]
    if a.emotion in ("thanks",):
        return ["どういたしまして", "😊"]
    if a.emotion == "excited":
        return ["おめでとう！🎉", "すごい！", "ありがとう！"]
    if a.emotion == "love":
        return ["ありがとう！", "❤", "いただきます！"]
    if a.emotion == "sad":
        return ["大丈夫？", "元気出して", "電話しようか？"]
    if a.emotion == "worried":
        return ["大丈夫！", "気をつける", "ありがとう"]
    if a.emotion == "angry":
        return ["ごめんなさい", "今やる！", "あとでやる"]
    if a.emotion == "sleepy":
        return ["おやすみ", "おやすみ〜💤"]
    if t.rstrip().endswith(("?", "？")):
        return ["うん", "ううん", "あとで返事する"]
    if _find(t, REQUEST_WORDS):
        return ["了解！", "あとでやる", "ごめん無理"]
    return ["了解！", "ありがとう", "👍"]


def detect_mentions(text: str, members: list[dict]) -> list[str]:
    """Return user ids of members whose nickname or name appears as @name or name+呼びかけ."""
    ids = []
    for m in members:
        for label in {m.get("nickname") or "", m.get("name") or ""}:
            if label and (f"@{label}" in text or re.search(re.escape(label) + r"(へ|に|さん|ちゃん|くん)?[、,]", text)):
                ids.append(m["user_id"])
                break
    return ids
