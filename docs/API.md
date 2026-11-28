# השרת: API, MCP וחיבור אייג'נטים

<div dir="rtl">

השרת הוא Edge Function אחת ב-Supabase (פרויקט `vybivdkskrkafcuedvbg`, אזור eu-central-1). הקוד נמצא ב-`supabase/functions/parking/`, והנתונים בסכמה פרטית `parking`.

כתובת הבסיס:

```
https://vybivdkskrkafcuedvbg.supabase.co/functions/v1/parking
```

## הרשאות

יש שלושה סוגי גישה:

| מי | איך | מה מותר |
|---|---|---|
| **האפליקציה (בעלים)** | `Authorization: Bearer <owner token>` | הכול |
| **אייג'נט מחובר** (למשל מורטי) | `Authorization: Bearer <agent token>` | קריאה בלבד: כלים, MCP וערוץ ההתראות |
| **ציבורי** | בלי כותרת | `/`, `/health`, `/api/register` (פעם אחת בלבד), `/link/claim` עם קוד חד-פעמי |

- **טוקן הבעלים** נוצר בפעם הראשונה שהאפליקציה פונה ל-`/api/register`. כל ניסיון נוסף מקבל 409.
- **טוקן אייג'נט** נוצר כשאייג'נט פודה קוד חיבור. יש אחד לכל אייג'נט, וחיבור מחדש מחליף אותו.
- אייג'נט שמנסה פעולה של בעלים מקבל 403.

## נקודות קצה

### ציבוריות

| שיטה ונתיב | מה עושה |
|---|---|
| `GET /` | שם, גרסה וכתובת ה-MCP |
| `GET /health` | `{ ok, version, zones, nightLots, publicLots, loadingSigns, autotel, source }`. `source` אומר לכל שכבה אם נטענה מה-GIS של העירייה (`live`) או מהעותק הלילי בריפו (`snapshot`). |
| `POST /api/register` | `{ token }` בפעם הראשונה, אחר כך 409 |
| `POST /link/claim` | אייג'נט פודה קוד חיבור. ראו "חיבור אייג'נט". |

### בעלים או אייג'נט

| שיטה ונתיב | מה עושה |
|---|---|
| `POST /mcp` | MCP ב-JSON-RPC, בלי session: `initialize`, `tools/list`, `tools/call` |
| `GET /api/tools` | רשימת הכלים עם הסכמות שלהם |
| `POST /api/tool/<name>` | הרצת כלי. הגוף הוא אובייקט JSON של הארגומנטים, והתשובה JSON דחוס. |
| `GET /api/feed?after=<id>` | התראות שנוספו אחרי `id`, עד 50, מהישנה לחדשה: `[{ id, time, kind, risk, text }]` |

### בעלים בלבד

| שיטה ונתיב | מה עושה |
|---|---|
| `POST /api/events` | `{ events: [...] }` מעלה חניות (upsert לפי `id`) |
| `GET /api/events` | כל החניות, מהחדשה לישנה |
| `DELETE /api/events/<id>` | מוחק חניה |
| `POST /api/curbs` | `{ curbs: [...], deleted: [ids] }` סימוני מדרכה ומחיקות |
| `POST /api/profile` | `{ assignedLots: [names] }` חניונים "קרוב לבית" שמשויכים לבעלים | נוספים על אלה שבמכתב התו (`config/rules.json`, `profile.permit`) 
| `POST /api/link/start` | `{ payload, expiresInSeconds }`: קוד חיבור חד-פעמי לעשר דקות |
| `GET /api/link/status` | `{ linked, agents: [{ client, since, lastSeen }] }` |
| `POST /api/link/revoke` | `{ client? }` מנתק אייג'נט אחד, או את כולם. מחזיר `{ ok, left }`. |
| `POST /api/notify` | `{ text, kind, risk }` מוסיף התראה לערוץ. `risk` הוא `low`, `medium` או `high`. |

## כלים (MCP ו-`/api/tool`)

כתובות הן רחוב ומספר בית בעברית, למשל `דיזנגוף 50`. זמנים הם שעון ישראל בלי אזור זמן, למשל `2026-11-21T20:00`.

| כלי | ארגומנטים | מה מחזיר |
|---|---|---|
| `parking_near_destination` | `address` או `lat`+`lon`, `arrival`, `minutes` | חוק הרחוב והעלות ביעד, חניונים עם תפוסה חיה, חניונים "קרוב לבית" שמשויכים לבעלים, חניונים נוספים, תמרורי פריקה וטעינה, אוטותל, סימוני מדרכה, היסטוריה וחדשות ליד היעד. כולל רשימת `options` מסודרת מהזול ליקר. |
| `where_is_my_car` | – | החניה האחרונה, עם קישור לניווט רגלי |
| `parking_history` | `limit` | היסטוריית חניות |
| `parking_stats` | `lat`, `lon`, `radiusMeters` (רשות) | חניות לפי סוג, הרחובות שבהם החיפוש היה הכי מהיר, חיפוש לפי שעה ביום |
| `curb_map_near` | `lat`, `lon`, `radiusMeters` | סימוני מדרכה לפי רחוב |
| `garages_near` | `lat`, `lon`, `radiusMeters` | חניוני אחוזות החוף עם תפוסה, תעריפים והנחת תושב |
| `other_lots_near` | `lat`, `lon`, `radiusMeters` | חניונים ציבוריים של העירייה |
| `loading_signs_near` | `lat`, `lon`, `radiusMeters` | תמרורי פריקה וטעינה עם הטקסט שעליהם |
| `zone_at` | `lat`, `lon` | אזור, האם זה האזור של התו, והאם זה רחוב גובל |
| `street_parking_cost` | `lat`, `lon`, `start`, `minutes`, `freeMinutesUsedToday` | דקות חינם ובתשלום, עלות, ו-`appRequired` |
| `parking_notices` | – | חדשות חניה: חניה חינם, מחירים, אירועים |
| `geocode` | `address` | קואורדינטות מנתוני הכתובות של העירייה |

`costILS: null` אומר שהתעריף לא מאומת. לא ממציאים מחיר במקומו.

## חיבור אייג'נט בלחיצה (פרוטוקול link)

1. באפליקציה לוחצים "מורטי בטלגרם" ואז "חבר". האפליקציה קוראת ל-`POST /api/link/start` ומקבלת `payload`:
   ```
   link-sb-<project ref>-<function>-<code>
   ```
   למשל `link-sb-vybivdkskrkafcuedvbg-parking-aPF9Zz7Q5UwawRDY`. אורכו עד 64 תווים, כמו שטלגרם מאפשר.
2. האפליקציה פותחת את `https://t.me/<bot>?start=<payload>`. ברירת המחדל לבוט היא `YishaiMortybot`.
3. מורטי מקבל `/start <payload>`, רק מהצ'אט של הבעלים, וקורא ל:
   ```
   POST https://<project ref>.supabase.co/functions/v1/<function>/link/claim
   { "code": "<code>", "client": "morty" }
   ```
4. התשובה:
   ```json
   { "name": "parking", "title": "חניה בתל אביב", "base": "https://…/parking",
     "token": "…", "auth": { "header": "Authorization", "scheme": "Bearer" },
     "guide": "…", "feed": "/api/feed", "cursor": 0, "hello": "…" }
   ```
5. מורטי שומר את הטוקן כסוד `link_parking`, מתיר לשלוח אותו רק לשרת שהנפיק אותו, ומוסיף את `guide` להקשר של הסוכנים שלו. מאז הוא בודק את `feed` פעם בדקה.

הקוד חד-פעמי ותקף עשר דקות. "נתק" באפליקציה מבטל את הטוקן. מורטי מזהה את זה (401) ומוחק אותו גם אצלו.

## ערוץ ההתראות

| מקור | `kind` | `risk` |
|---|---|---|
| תזכורות פנגו וזמן חינם מהטלפון | `pango` | `high` (עובר את שעות השקט של מורטי) |
| חניון שהתפנה או התמלא | `app` | `medium` |
| חניה חינם בחדשות (pg_cron, כל שעה) | `news` | `medium` |
| שינוי מחיר בחדשות | `news` | `low` |
| סיכום שבועי (pg_cron, ראשון 08:30) | `weekly` | `low` |

- התראות נשמרות רק כשיש אייג'נט מחובר, ונמחקות אחרי 14 יום.
- משימות ה-cron נקבעות בחיבור האייג'נט הראשון ומבוטלות כשהאחרון מתנתק.
- הן נקראות דרך `/cron/<job>/<secret>`, כש-`<secret>` אקראי ושמור בטבלת ההגדרות.

## טבלאות (סכמה `parking`)

| טבלה | תוכן |
|---|---|
| `owner` | טוקן הבעלים |
| `events` | חניות (`id`, `time`, `data` jsonb) |
| `curbs` | סימוני מדרכה (`id`, `time`, `data` jsonb) |
| `settings` | `assignedLots`, `linkCode`, `cronSecret`, `seenNews` |
| `agents` | טוקנים של אייג'נטים מחוברים (`token`, `name`, `created`, `last_seen`) |
| `notifications` | ערוץ ההתראות (`id`, `time`, `kind`, `risk`, `text`) |

לכל הטבלאות מופעל RLS, ו-anon ו-authenticated לא מקבלים גישה. רק הפונקציה ניגשת אליהן, דרך `SUPABASE_DB_URL`.

</div>
