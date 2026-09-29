#!/usr/bin/env python3
"""Builds app/src/main/assets/cities_sa.json: Saudi cities the challenges cover.

Source: GeoNames (CC BY 4.0) country extract SA.txt and alternatenames/SA.txt from
https://download.geonames.org/export/dump/. Populated places (PPL, PPLA, PPLA2, PPLC) with at
least 10,000 people, plus governorate seats that GeoNames lists without a population. Names are
cleaned (Arabic without diacritics, English without accents); coordinates always come from GeoNames.

usage: build_cities.py <dir with SA.txt and alt/SA.txt> <output json>
"""
import collections, csv, json, math, re, sys, unicodedata

src, out_path = sys.argv[1], sys.argv[2]
REGIONS = {  # GeoNames admin1 code -> (Arabic, English)
    "02": ("الباحة", "Al Bahah"), "05": ("المدينة المنورة", "Madinah"), "06": ("الشرقية", "Eastern Province"),
    "08": ("القصيم", "Qassim"), "10": ("الرياض", "Riyadh"), "11": ("عسير", "Asir"), "13": ("حائل", "Hail"),
    "14": ("مكة المكرمة", "Makkah"), "15": ("الحدود الشمالية", "Northern Borders"), "16": ("نجران", "Najran"),
    "17": ("جازان", "Jazan"), "19": ("تبوك", "Tabuk"), "20": ("الجوف", "Al Jawf"),
}
# Districts of a bigger city, a military city, and duplicate / uncertain entries.
EXCLUDE = {"101760", "104828", "101313", "110059", "101581"}
# Governorate seats GeoNames lists without a population: (English name, admin1 code, Arabic name, region override).
ADD_BY_NAME = [
    ("Unaizah", "08", "عنيزة", None), ("Tathlīth", "11", "تثليث", None), ("Khaybar", "05", "خيبر", None),
    ("Al Majma‘ah", "10", "المجمعة", None), ("Al Khamāsīn", "10", "وادي الدواسر", None),
    ("Muḩāyil", "00", "محايل عسير", "11"), ("Sharurah", "16", "شرورة", None), ("Al Qunfudhah", "00", "القنفذة", "14"),
    ("Rafha", "15", "رفحاء", None), ("Al Muzāḩimīyah", "10", "المزاحمية", None), ("Thādiq", "10", "ثادق", None),
    ("Ḩuraymilā’", "10", "حريملاء", None), ("Baysh", "17", "بيش", None), ("Ad Darb", "17", "الدرب", None),
    ("Al ‘Uwayqīlah", "15", "العويقيلة", None), ("Sabt Alalayah", "11", "سبت العلايا", None),
    ("Tanūmah", "00", "تنومة", "11"), ("Dawmat al Jandal", "20", "دومة الجندل", None),
    ("Al Quway‘īyah", "10", "القويعية", None), ("Al Ghāţ", "10", "الغاط", None), ("Ḩawţat Sudayr", "10", "حوطة سدير", None),
    ("Ḑurumā", "10", "ضرما", None), ("Marāt", "10", "مرات", None), ("Al ‘Aydābī", "00", "العيدابي", "17"),
    ("Ar Rayth", "17", "الريث", None), ("Mawqaq", "13", "موقق", None), ("Simīrā’", "13", "سميراء", None),
    ("Qaryah al ‘Ulyā", "06", "قرية العليا", None),
]
AR_FIX = {  # geonameid -> Arabic name where GeoNames has none, a Latin one, or a short form
    "109223": "المدينة المنورة", "101732": "عنيزة", "110325": "الدوادمي", "101554": "تاروت", "12495725": "بارق",
    "103035": "رابغ", "109253": "الليث", "110619": "أبو عريش", "109380": "الخفجي", "110250": "عفيف",
    "102985": "رحيمة", "101516": "تيماء", "13631408": "حوطة بني تميم", "104716": "الأفلاج (ليلى)",
    "109306": "الخرمة", "107744": "بدر", "409682": "ثول", "102451": "صامطة", "106102": "حقل", "102744": "رماح",
    "108048": "السليل", "101322": "تربة", "104578": "مهد الذهب", "104923": "خليص", "109915": "البطالية",
    "109059": "المنيزلة", "399518": "المجاردة", "108890": "القرين", "101035": "أم الساهك", "103220": "قبة",
    "106744": "فرسان", "11524299": "مدينة الملك عبدالله الاقتصادية", "110107": "الأوجام", "106909": "ضباء",
    "101312": "طريف", "393275": "تربة", "397833": "البدائع", "11835536": "رياض الخبراء",
}
REGION_FIX = {"106102": "19", "12546009": "14"}  # Haql is in Tabuk, Ranyah in Makkah (GeoNames has them elsewhere)
EN_FIX = {"104515": "Makkah", "109223": "Madinah", "107968": "Taif", "106281": "Hail",
          "104716": "Al Aflaj (Layla)", "106102": "Haql", "107744": "Badr"}
ALIASES = {"109571": ["الأحساء", "Al Ahsa", "Al Hasa"], "109101": ["الأحساء", "Al Ahsa", "Al Hasa"],
           "104515": ["مكة", "Makkah"], "109223": ["المدينة", "Madinah"], "110336": ["Dammam"],
           "104716": ["ليلى", "Layla", "Al Aflaj"], "107968": ["Taif"], "106281": ["Hail"]}

def clean_ar(s):
    s = s.replace("‎", "").replace("‏", "").replace("ٲ", "أ").strip()
    return "".join(ch for ch in s if not ("ً" <= ch <= "ْ" or ch == "ٰ"))

def clean_en(s):
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    return s.replace("‘", "").replace("’", "").replace("`", "").replace("ʼ", "").strip()

rows = {r[0]: r for r in csv.reader(open(f"{src}/SA.txt", encoding="utf8"), delimiter="\t") if r[6] == "P" and r[8] == "SA"}
alt = collections.defaultdict(list)
for a in csv.reader(open(f"{src}/alt/SA.txt", encoding="utf8"), delimiter="\t"):
    if a[2] in ("ar", "en") and a[1] in rows and a[6] != "1" and a[7] != "1":
        alt[a[1]].append(a)

def best(gid, lang):
    c = [a for a in alt[gid] if a[2] == lang]
    return ((([a for a in c if a[4] == "1"] or c) or [None])[0] or [None] * 4)[3]

AR = re.compile("[؀-ۿ]")
picked = {}  # gid -> (row, arabic, region code, population)
for gid, r in rows.items():
    if r[7] in ("PPL", "PPLA", "PPLA2", "PPLC") and int(r[14] or 0) >= 10000 and gid not in EXCLUDE:
        ar = AR_FIX.get(gid) or best(gid, "ar")
        picked[gid] = (r, ar, r[10], int(r[14]))
for en, code, ar, region in ADD_BY_NAME:
    m = [r for r in rows.values() if r[1] == en and r[10] == code]
    m.sort(key=lambda r: (r[7] != "PPLA2", -int(r[14] or 0)))
    if not m:
        sys.exit(f"not found: {en} ({code})")
    r = m[0]
    if r[1] == "Unaizah":  # precise seat coordinates replace the rounded PPL entry
        picked.pop("101732", None)
    picked[r[0]] = (r, ar, region or r[10], max(int(r[14] or 0), 20000))
    if en == "Al Khamāsīn":
        EN_FIX[r[0]] = "Wadi ad-Dawasir"

cities = []
for gid, (r, ar, code, pop) in picked.items():
    if not ar or not AR.search(ar):
        sys.exit(f"no Arabic name for {gid} {r[1]}")
    region = REGIONS[REGION_FIX.get(gid, code)]
    radius = min(40.0, max(5.0, 4 + 2.2 * math.sqrt(pop / 10000)))
    cities.append({
        "id": gid, "ar": clean_ar(ar), "en": EN_FIX.get(gid) or clean_en(best(gid, "en") or r[1]),
        "regionAr": region[0], "regionEn": region[1],
        "lat": round(float(r[4]), 5), "lon": round(float(r[5]), 5), "radiusKm": round(radius, 1), "population": pop,
        "aliases": ALIASES.get(gid, []),
    })
cities.sort(key=lambda c: -c["population"])
# Same Arabic name twice: add the region so the list can tell them apart.
dups = collections.Counter(c["ar"] for c in cities)
for c in cities:
    if dups[c["ar"]] > 1:
        c["ar"] = f"{c['ar']} ({c['regionAr']})"
        c["en"] = f"{c['en']} ({c['regionEn']})"
json.dump({"source": "GeoNames (CC BY 4.0), https://www.geonames.org", "cities": cities},
          open(out_path, "w", encoding="utf8"), ensure_ascii=False, indent=1)
print(len(cities), "cities written to", out_path)
