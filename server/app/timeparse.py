"""Japanese date/time extraction for fridge memos and the AI chibi.

    parse("15日16時：カレー作っておいたから食べてね")
      -> when = <this month 15th 16:00>, text = "カレー作っておいたから食べてね"

Supports: 今日/明日/明後日/今夜/今晩, N日, M月N日, M/N, 曜日 (来週の土曜), HH:MM, N時(半|M分),
午前/午後/朝/昼/夕方/夜, N分後/N時間後. Everything is local time (Asia/Tokyo by default).
"""
from __future__ import annotations

import os
import re
import unicodedata
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

TZ = ZoneInfo(os.environ.get("FAMICHIBI_TZ", "Asia/Tokyo"))
WEEKDAYS = "月火水木金土日"
KANJI_NUM = {"〇": 0, "零": 0, "一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}


@dataclass
class Parsed:
    when: datetime | None
    date_only: bool
    text: str
    matched: str = ""
    day: date | None = None

    @property
    def ts(self) -> float | None:
        return self.when.timestamp() if self.when else None


def _kanji_to_int(s: str) -> str:
    def conv(m: re.Match) -> str:
        k = m.group(0)
        if k == "十":
            return "10"
        if k.startswith("十"):
            return str(10 + KANJI_NUM[k[1]])
        if k.endswith("十"):
            return str(KANJI_NUM[k[0]] * 10)
        if "十" in k:
            a, b = k.split("十")
            return str(KANJI_NUM[a] * 10 + KANJI_NUM[b])
        return "".join(str(KANJI_NUM[c]) for c in k)
    return re.sub(r"[〇零一二三四五六七八九十]+(?=[時分日月])", conv, s)


def now_local() -> datetime:
    return datetime.now(TZ)


def parse(text: str, base: datetime | None = None) -> Parsed:
    base = base or now_local()
    raw = text
    s = unicodedata.normalize("NFKC", text)
    s = _kanji_to_int(s)
    spans: list[tuple[int, int]] = []
    day: date | None = None
    hour: int | None = None
    minute = 0
    relative: timedelta | None = None

    def take(m: re.Match | None) -> re.Match | None:
        if m:
            spans.append(m.span())
        return m

    # relative: N分後 / N時間後
    m = take(re.search(r"(\d+)\s*(分|時間)(後|ご)", s))
    if m:
        n = int(m.group(1))
        relative = timedelta(minutes=n) if m.group(2) == "分" else timedelta(hours=n)

    # dates
    m = take(re.search(r"(\d{1,2})\s*月\s*(\d{1,2})\s*日", s)) or take(re.search(r"(?<![\d:])(\d{1,2})/(\d{1,2})(?![\d/])", s))
    if m:
        mo, d = int(m.group(1)), int(m.group(2))
        try:
            day = date(base.year, mo, d)
            if day < base.date() - timedelta(days=1):
                day = date(base.year + 1, mo, d)
        except ValueError:
            day = None
    if day is None:
        m = take(re.search(r"(今日|きょう|本日|今夜|今晩|こんや|明日|あした|あす|明後日|あさって)", s))
        if m:
            w = m.group(1)
            off = 0 if w in ("今日", "きょう", "本日", "今夜", "今晩", "こんや") else 1 if w in ("明日", "あした", "あす") else 2
            day = base.date() + timedelta(days=off)
            if w in ("今夜", "今晩", "こんや") and hour is None:
                hour = 20
    if day is None:
        m = take(re.search(r"(?<![\d月/])(\d{1,2})\s*日(?!間)", s))
        if m:
            d = int(m.group(1))
            try:
                cand = date(base.year, base.month, d)
                if cand < base.date():
                    ny, nm = (base.year + 1, 1) if base.month == 12 else (base.year, base.month + 1)
                    cand = date(ny, nm, d)
                day = cand
            except ValueError:
                day = None
    if day is None:
        m = take(re.search(r"(来週|再来週|今週)?の?\s*([月火水木金土日])曜日?", s))
        if m:
            target = WEEKDAYS.index(m.group(2))
            delta = (target - base.weekday()) % 7
            if m.group(1) == "来週":
                delta = (7 - base.weekday()) + target
            elif m.group(1) == "再来週":
                delta = (14 - base.weekday()) + target
            day = base.date() + timedelta(days=delta)

    # time of day
    period = None
    pm = re.search(r"(午前|午後|朝|昼|夕方|夜|深夜)", s)
    if pm:
        period = pm.group(1)
    m = take(re.search(r"(\d{1,2}):(\d{2})", s))
    if m:
        hour, minute = int(m.group(1)), int(m.group(2))
    else:
        m = take(re.search(r"(\d{1,2})\s*時\s*(半|(\d{1,2})\s*分)?", s))
        if m:
            hour = int(m.group(1))
            if m.group(2) == "半":
                minute = 30
            elif m.group(3):
                minute = int(m.group(3))
    if hour is not None and period:
        if period in ("午後", "夕方", "夜") and hour < 12:
            hour += 12
        if period == "深夜" and hour == 12:
            hour = 0
        spans.append(pm.span())
    elif hour is None and period and day is not None:
        hour = {"朝": 7, "午前": 9, "昼": 12, "午後": 15, "夕方": 17, "夜": 20, "深夜": 23}[period]
        spans.append(pm.span())
    if hour is not None and not period and hour < 7 and not m.group(0).startswith("0"):
        # "3時" without am/pm in a family memo almost always means the afternoon.
        hour += 12

    when: datetime | None = None
    date_only = False
    if relative is not None:
        when = base + relative
    elif day is not None or hour is not None:
        d = day or base.date()
        if hour is None:
            date_only = True
            when = datetime(d.year, d.month, d.day, 9, 0, tzinfo=TZ)
        else:
            hour = min(hour, 23)
            when = datetime(d.year, d.month, d.day, hour, minute, tzinfo=TZ)
            if day is None and when < base - timedelta(minutes=1):
                when += timedelta(days=1)

    # Remove the date/time prefix from the memo text ("15日16時：カレー..." -> "カレー...")
    cleaned = raw
    matched = ""
    if spans:
        start = min(a for a, _ in spans)
        end = max(b for _, b in spans)
        norm_prefix = s[:start].strip()
        if not norm_prefix and start == 0:
            rest = s[end:]
            rest = re.sub(r"^(に|の|は|から|まで)?\s*[:：、,\s]*", "", rest)
            matched = s[:end]
            cleaned = rest or raw
    return Parsed(when=when, date_only=date_only, text=cleaned.strip(), matched=matched, day=(when.date() if when else None))


def fmt(ts: float | None) -> str:
    if not ts:
        return ""
    d = datetime.fromtimestamp(ts, TZ)
    wd = WEEKDAYS[d.weekday()]
    return f"{d.month}/{d.day}({wd}) {d.hour}:{d.minute:02d}"


def day_bounds(d: date) -> tuple[float, float]:
    a = datetime(d.year, d.month, d.day, tzinfo=TZ)
    return a.timestamp(), (a + timedelta(days=1)).timestamp()
