# parking-tlv

חניה בתל אביב: סנכרון שכבות GIS עירוניות, מנוע חוקים לבעל תו אזור 9, ושרת MCP. השלב הבא: אפליקציית אנדרואיד + Android Auto (קטגוריית POI).

## הרצה
```
npm install
npm run sync     # מושך את השכבות ל-data/*.json (צריך גישה ל-gisn.tel-aviv.gov.il)
npm test
npm run mcp      # שרת MCP ב-stdio
```

## מקורות (ציבוריים, בלי מפתח)
שרת ArcGIS: `https://gisn.tel-aviv.gov.il/arcgis/rest/services/IView2/MapServer`

| שכבה | id | שימוש |
|---|---|---|
| אזורי חניה | 544 | זיהוי אזור לפי נקודה |
| חניוני אחוזות החוף | 970 | תעריפים וסטטוס תפוסה (`status_chenyon`, רעננות ב-`tr_status_chenyon`) |
| חניוני לילה לתושבים | 488 | "קרוב לבית" |

## מה מאומת ומה לא
- מאומת: חינם באזור התו כל שעות היממה; מחוץ לאזור שעתיים חינם מצטברות ביממה ואז 30% הנחה מתעריף מבקר.
- לא מאומת (ב-`config/rules.json`): תעריף מבקר לשעה, שעת סיום חלון התשלום. כל עוד התעריף `null` המנוע מחזיר `costILS: null` ולא מנחש.
- לא נמצאה עדיין שכבת כחול-לבן ברמת הרחוב.
- חניית חינם בחירום: דגל `freeParkingOverride`.

## כלי MCP
`zone_at`, `street_parking_cost`, `garages_near`, `night_lots_near`.
