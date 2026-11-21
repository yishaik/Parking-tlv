# פיתוח, בדיקות ושחרור

<div dir="rtl">

## מבנה הריפו

| תיקייה | תוכן |
|---|---|
| `android/` | האפליקציה (Kotlin, Compose). `android/VERSION` קובע את הגרסה. |
| `supabase/functions/parking/` | השרת |
| `src/` | מנוע החוקים והגאומטריה ב-TypeScript, ו-`gis.ts` לקריאת שכבות העירייה |
| `scripts/` | `sync.ts` (עותק לילי), `holidays.ts`, `gen-function-config.ts`, `gen-rule-vectors.ts` |
| `config/` | `rules.json` (החוקים), `holidays.json`, `places.json` (נורדאו 86, אוסישקין 54), `server.json` |
| `data/` | עותק לילי של שכבות העירייה, נארז באפליקציה |
| `test/` | בדיקות vitest |
| `docs/` | התיעוד והצילומים |

## בדיקות

```
npm install
npm test            # vitest: חוקים, כלי השרת, וקטורים משותפים, עקביות העותקים, ונתוני אמת
npm run typecheck
```

אנדרואיד (JDK 17, Gradle 8.10):

```
gradle -p android testDebugUnitTest lintDebug
```

- **`RulesVectorsTest`:** החוקים ב-Kotlin עונים כמו ב-TypeScript.
- **`RulesBehaviourTest`:** האזור שלך, רחוב גובל, מתג חניה חינם, שעות "קרוב לבית" וחגים.
- **`DataTest`:** שתי הכתובות שלך באזור 9, ושקובץ פגום לא נמחק.

**הרצה על אמולטור:** `ScreenshotTest` מכניס נתוני דוגמה ועובר על כל המסכים. הוא רץ ב-workflow `screenshots`. כדי להריץ אותו בלי לשחרר גרסה:

```
git push origin main:screenshots
```

- הצילומים נשמרים ב-`docs/screenshots` ב-main.
- גם כשריצה נכשלת, הצילומים שלה נשמרים בענף `screenshots-out`, כולל `fail-*.png` במקום שבו נתקעה.
- בבדיקה הזו האפליקציה מקבלת טוקן דמה, אז שום דבר לא מגיע לשרת האמיתי.

## שחרור גרסה

גרסה נבנית רק כשהבעלים אומר "לבנות".

1. מחליפים את "## הגרסה הבאה" ב-`CHANGELOG.md` בגרסה ובתאריך, למשל `## 0.6.0 · 22.11.2026`.
2. מעדכנים את `android/VERSION`, באותו commit.
3. דוחפים:
   ```
   git push origin main
   git push origin main:release
   ```

אחר כך הכול קורה לבד:
- `android` מריץ בדיקות, בונה, ויוצר Release `v0.6.0` עם `parking-tlv-v0.6.0.apk`. ההערות נלקחות מהסעיף בצ'יינג'לוג.
- `screenshots` מצלם מחדש.
- קוד הגרסה מחושב מהגרסה (0.6.0 → 600). לכן גרסה חדשה חייבת להיות גבוהה מהקודמת.
- ה-APK חתום במפתח קבוע (`android/app/debug.keystore`), כך שהוא מותקן מעל הקיים בלי למחוק נתונים.

## שינוי בשרת

1. משנים קוד ב-`supabase/functions/parking/`. שינוי ב-`rules.ts` או ב-`geo.ts` נעשה ב-`src/` ומועתק, ו-`npm test` נכשל אם שכחו להעתיק.
2. שינוי ב-`config/rules.json` או בחגים מחייב `npm run gen-config`.
3. בודקים:
   ```
   npm test
   deno check --no-config supabase/functions/parking/index.ts
   ```
4. פורסים את כל הקבצים בתיקייה דרך ה-MCP של Supabase: `deploy_edge_function`, `verify_jwt: false`.
5. דוחפים. `server-health` בודק את השרת שבאוויר.

## שינוי בחוקים

1. מעדכנים את `config/rules.json`.
2. מייצרים מחדש: `npm run gen-config` ו-`npx tsx scripts/gen-rule-vectors.ts`.
3. בודקים שההבדלים בווקטורים הם בדיוק מה שהתכוונו לשנות.
4. מריצים את הבדיקות בשני הצדדים, ואז פורסים את השרת ובונים את האפליקציה.

## נתוני העירייה

- `sync-gis` רץ כל לילה ב-03:17 UTC, ושומר שכבה רק אם היא הגיעה.
- אם ה-GIS נופל בזמן ריצה, השרת נטען מהעותק ב-`data/` (`/health` מראה `snapshot`). האפליקציה תמיד משתמשת בעותק שארוז בה, ורק את התפוסה החיה היא קוראת מהרשת.

## סודות ומפתחות

| מה | איפה |
|---|---|
| טוקן הבעלים | ב-prefs של האפליקציה (`sync`) ובטבלה `parking.owner`. גיבוי אוטומטי של אנדרואיד מחזיר אותו בהתקנה מחדש. |
| טוקן של אייג'נט | `parking.agents`, ואצל מורטי כסוד `link_parking` |
| מפתח החתימה | `android/app/debug.keystore`, בריפו בכוונה (אפליקציה אישית שמותקנת ידנית) |

**בעלים חדש:** אם הטוקן אבד (למשל התקנה נקייה בלי גיבוי), מוחקים את השורה ב-`parking.owner`. ההתקנה הבאה של האפליקציה תירשם מחדש כבעלים.

</div>
