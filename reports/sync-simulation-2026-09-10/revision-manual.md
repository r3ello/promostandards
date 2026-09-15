# Productos pendientes de migrar — qué revisar a mano (2026-09-11)

De los 1022 productos PromoStandards de la tienda se migraron los 234 sanos; quedan **788**. Clasificación a partir de la simulación del 2026-09-10 (`simulation.md` en esta carpeta); la tienda no ha cambiado desde entonces salvo por esos 234. Lista completa, una fila por producto: `revision-manual.csv` (columna `revisar_a_mano`; `admin` abre la búsqueda por SKU legacy).

| grupo | productos | ¿a mano? |
|---|---|---|
| 1. Sin rastro en PaceSetter — archivar o buscar el código nuevo | 219 | **sí** |
| 2. Un producto PaceSetter repartido en varios de Shopify — fusionar o sincronizar sólo sus partes | 236 | **sí** |
| 3. Agrupa artículos distintos de PaceSetter — separar o aceptar la agrupación | 2 | **sí** |
| 4. Mezcla ids vivos y muertos — comprobar que el producto sigue siendo lo que anuncia | 16 | **sí** |
| 5. Repunteo simple — cambiar ps_product_ids al producto PaceSetter (CSV de Matrixify) | 110 | no |
| 6. Espera el arreglo de la unión de familias (código) — no sincronizar hasta entonces | 134 | no |
| 7. Varias medidas del mismo artículo — migrables ya | 71 | no |

**A revisar a mano: 473.** Títulos repetidos entre ellos: 21 (columna `titulo_duplicado`).

## 2. Repartidos — 76 decisiones, no 236

Cada línea es una decisión: los productos de Shopify que venden trozos del mismo producto PaceSetter.

| producto(s) PaceSetter | productos de Shopify |
|---|---|
| `C609LB C610LB C612LB C701BL C701L` | 16: Black Chisel Carve Tower Award on Base; Blue Chisel Carve Tower Award; Blue Chisel Carved Tower Award On Base; Blue Spectrum Chisel Carved Tower Small; Gold Chisel Carve Tower Award; Gold Chisel Carved Tower On Base Large; Gold Chisel Carved Tower on Base Award; Gold Spectrum Chisel Carved Tower Award; Green Chisel Carve Tower Award; Green Chisel Carve Tower Award on Base; Purple Chisel Carve Tower Award; Purple Chisel Carve Tower Award on Base; Red Chisel Carve Tower Award on Base; Red Spectrum Chisel Carved Tower Award; Teal Chisel Carve Tower Award; Teal Chisel Carve Tower Award on Base |
| `CD953AK/CD953AL/CD953AR/CD953AZ/CD953CO/CD953CT/CD953GA/CD953IA/CD953IL/CD953IN/CD953KS/CD953KY/CD953LA/CD953ME/CD953MN/CD953MO/CD953MS/CD953MT/CD953ND/CD953NE/CD953NH/CD953NJ/CD953NM/CD953NV/CD953NY/CD953OH/CD953OK/CD953OR/CD953PA/CD953RI/CD953SC/CD953SD/CD953TX/CD953UT/CD953VA/CD953VT/CD953WA/CD953WI/CD953WV/CD953WY` | 10: Frosted Acrylic CA State Cutout on Black Plaque; Frosted Acrylic DE State Cutout on Black Plaque; Frosted Acrylic FL State Cutout on Black Plaque; Frosted Acrylic HI State Cutout on Black Plaque; Frosted Acrylic ID State Cutout on Black Plaque; Frosted Acrylic MA State Cutout on Black Plaque; Frosted Acrylic MD State Cutout on Black Plaque; Frosted Acrylic MI State Cutout on Black Plaque; Frosted Acrylic NC State Cutout on Black Plaque; Frosted Acrylic TN State Cutout on Black Plaque |
| `C750` | 10: Blue Halley Collection Plaque; Large Gold Halley Plaque; Large Green Halley Plaque; Large Red Halley Plaque; Medium Gold Halley Plaque; Medium Green Halley Plaque; Medium Red Halley Plaque; Small Gold Halley Plaque; Small Green Halley Plaque; Small Red Halley Plaque |
| `CM291*` | 9: Black/Silver Leatherette Oval Keychain; Cork Leatherette Oval Keychain; Dark Brown Leatherette Oval Keychain; Grey Leatherette Oval Keychain; Leatherette Oval Keychain; Light Brown Leatherette Oval Keychain; Pink Leatherette Oval Keychain; Rawhide Leatherette Oval Keychain; Rose Leatherette Oval Keychain |
| `C023` | 6: Blue Plaque w/ Marble Mist Large; Blue Plaque w/ Marble Mist Small; Genuine Walnut Elliptical Plaque with Marble Mist; Plaque w/ Green Marble Mist; Red Plaque w/ Marble Mist Small; Red Plaque with Marble Mist Large |
| `GI638` | 6: Chisel Carved Reflective Blue Bottom Award; Chisel Carved Reflective Green Bottom Award; Chisel Carved with Reflective Black Bottom Award; Chisel Carved with Reflective Gold Bottom Award; Chisel Carved with Reflective Red Bottom Award; Clear Crystal Chisel Carve Award |
| `CD547A CD547B CD547C` | 6: Digi-color Mahogany Plaque; Extra-Large Digi; Large Digi Large; Medium Digi; Walnut Finish  Digi; Walnut Finish Digi |
| `C071A C071B` | 6: Florentine Edge Plate on Ebony Board Medium; Florentine Gold Edge Plate on Ebony Board; Florentine Gold Edge Plate on Ebony Board Large; Walnut Finish Plaque with Black Florentine; Walnut Finish Plaque with Black Florentine; Walnut Finish Plaque with Black Florentine Plate |
| `CD414*` | 6: Blue Marble Florentine Plate on Ebony Board; Blue Marble Florentine Plate on Ebony Finish Board; Green Marble Florentine Plate on Ebony Board; Green Marble Florentine Plate on Walnut Finish Board; Red Marble Florentine Plate on Ebony Board; Red Marble Florentine Plate on Walnut Finish Board |
| `CM293` | 5: 7-Piece Black/Silver Leatherette Manicure Gift Set; 7-Piece Dark Brown Leatherette Manicure Gift Set; 7-Piece Light Brown Leatherette Manicure Gift Set; 7-Piece Pink Leatherette Manicure Gift Set; 7-Piece Rawhide Leatherette Manicure Gift Set |
| `CM296` | 5: Black/Silver Leatherette Phone Easel; Dark Brown Leatherette Phone Easel; Grey Leatherette Phone Easel; Light Brown Leatherette Phone Easel; Rawhide Leatherette Phone Easel |
| `GI595` | 4: Clear Glass with Bold Black Crystal Accent; Clear Glass with Bold Blue Crystal Accent; Clear Glass with Bold Green Crystal Accent; Clear Glass with Bold Red Crystal Accent |
| `C3501A C3501B C3501C` | 4: Ebony Finish Certificate/Overlay Plaque; Genuine Walnut Certificate Plaque; Walnut Finish Certificate/Overlay Plaque in Gift Box; Walnut Finish Certificate/Overlay Plaque in Mailer Box |
| `DCC3001BEB DCCD520` | 4: Choice of Digi Large; Choice of Digi Small; Ebony Finish Plaque with choice of plate and board; Ebony Piano Finish Plaque |
| `CD961` | 4: Layered Acrylic Plaque w/Black Back and Circle; Layered Acrylic Plaque w/Blue Back and Circle; Layered Acrylic Plaque w/Green and Circle; Layered Acrylic Plaque with Orange Back and Circle Award |
| `CM237A* CM237B*` | 4: Leatherette Portfolio; Leatherette Portfolio Small; Leatherette Portfolio with Notepad (lrg); Leatherette Portfolio with Notepad (sml) |
| `CD904AY*` | 3: Anniversary Freestanding 1 Year Award; Anniversary Freestanding 3 Year Award; Anniversary Freestanding 5 Year Award |
| `GI515` | 3: Black Curved Beveled; Blue Curved Beveled; Green Curved Beveled |
| `CD636` | 3: Blue Arched Brilliance Award on Clear Base; Green Arched Brilliance Award on Clear Base; Red Arched Brilliance Award on Clear Base |
| `CM240*` | 3: Cork Round Coaster; Leatherette Round Coaster; Leatherette Round Coaster |
| `C021A C021B` | 3: Ebony Finish Plaque with Marble Mist Large; Ebony Finish Plaque with Marble Mist Small; Walnut Finish Plaque with Marble Mist Plate |
| `C072` | 3: Genuine Walnut Elliptical Plaque with Black/Silver Florentine Large; Genuine Walnut Elliptical Plaque with Black/Silver Florentine Small; Gold Plaque w/ Black with Gold Florentine Plate |
| `GM453` | 3: Optic Clear Crystal Cube Award Trophy; Optic Clear Crystal Cube X Large Award Trophy; Optic Clear Crystal Cube XX Large Award |
| `CD1101A CD1101B CD1101C` | 3: Blue Acrylic Color Drops Award; Green Acrylic Color Drops Award; Red Acrylic Color Drops Award |
| `GM805` | 3: Blue Teardrop Art Glass; Green Teardrop Art Glass; Red Teardrop Art Glass |
| `GI673` | 3: Clear Crystal with Black Crystal Accents; Clear Crystal with Blue & Black Crystal Accents; Clear Crystal with Red & Black Crystal Accents |
| `CM455` | 3: Leatherette Money Clip Large; Leatherette Money Clip Large; Leatherette Money Clip Small |
| `CM754` | 3: Walnut Double Row Challenge Coin Display; Walnut Quadruple Row Challenge Coin Display; Walnut Triple Row Challenge Coin Display |
| `GI603` | 2: 3 Tier Glass Tower Award Amber; 3 Tier Glass Tower Blue Award |
| `CD854` | 2: Blue Deco Lucite Peak on Marble Base Award; Red Deco Lucite Peak On Marble Base Award Trophy |
| `CD852` | 2: Blue Designs Deco Lucite Tablet; Red Designs Deco Lucite Tablet Award Trophy |
| `CD603` | 2: Blue Gemstone Brilliance Lucite Peak Award; Green Gemstone Brilliance Lucite Peak Award |
| `CD848` | 2: Blue Lucite Rising Tide Wave Monument Award; Gold Lucite Rising Tide Wave Monument Trophy |
| `GI513A GI513B GI513C` | 2: Blue Optic Crystal Gemstone Award; Green Optic Crystal Gemstone Award |
| `GI512` | 2: Blue Vibrant Gemstone Award; Green Vibrant Gemstone Award |
| `CD476 CD477 CD478` | 2: Bronze Cascade Metal Stars Award; Gold Cascade Metal Stars Award |
| `GI583Y` | 2: Carved Clear Crystal Award on Black Base with Logo Medallion; Carved Clear Crystal on Black Base w/Digi |
| `CD947` | 2: Circle Cutout Alder Wood and Silver Backer Digi; Circle Cutout Mahogany Wood and Silver Backer Digi |
| `CM332* CM333` | 2: Cork Bottle Opener; Leatherette Bottle Opener |
| `CM282*` | 2: Cork Round; Leatherette Set of 6 Round Coasters |
| `CM241*` | 2: Cork Square Coaster; Leatherette Square Coaster |
| `CM283*` | 2: Cork Square Coasters Set; Leatherette Square Coasters Set |
| `GM722` | 2: Crystal Tapered Monolith; Crystal Tapered Monolith |
| `GI608` | 2: Crystal Towers w/ Black Jewel cut Pillar; Crystal Towers w/ White Jewel cut Pillar |
| `GM413` | 2: Florence Series Faceted Oval Crystal Large; Florence Series Faceted Oval Crystal Small |
| `CD945` | 2: Frosted Lucite on Black Piano Plaque and Black Plate; Frosted Lucite on Black Piano Plaque and Blue Plate |
| `C0642` | 2: Gold Star Self-Standing Plaque; Silver Star Self-Standing Plaque |
| `C610L` | 2: Medium Blue Chisel Carve Tower Award; Medium Gold Chisel Carve Tower Award |
| `GI29` | 2: Optic Crystal Wedge Award Large; Optic Crystal Wedge Award Trophy |
| `GM699` | 2: Premium Black Glass Tablet with Metal Stand; Premium Black Glass Tablet with Metal Stand Small |
| `GM698` | 2: Premium Blue Glass Tablet with Metal Stand; Premium Blue Glass Tablet with Metal Stand Small |
| `GM731` | 2: Rectangle Etched Edge Crystal with Layered Base; Rectangle Etched Edge Crystal with Layered Base Large |
| `CM401` | 2: Gold Metal Trophy Cup; Silver Metal Trophy Cup |
| `C076` | 2: Simplicity Plate on Ebony Board; Simplicity Plate on Ebony Board Plaque |
| `C075` | 2: Simplicity Plate on Walnut Finish Board; Simplicity Plate on Walnut Finish Board Plaque |
| `C4801A C4801AA C4801B C4801C` | 2: Slide-in Certificate Walnut Plaque in Gift Box; Slide-in Certificate Walnut Plaque in Mailer Box |
| `C4802` | 2: Black Certificate Frame with Silver Metallized Accent; Walnut Certificate Frame with Gold Metallized Accent |
| `GI672` | 2: Angular Clear/Black Glass Award; Angular Clear/Blue Glass Award |
| `CM750` | 2: Black Walnut Cutting & Charcuterie Board; Black Walnut Cutting & Charcuterie Board Large |
| `GM755` | 2: Crystal Tablet with Globe Large; Crystal Tablet with Globe Small |
| `GI674` | 2: Diamond Tower; Diamond Tower. Large |
| `CD1053` | 2: Golf Course Silhouette Award; Golf Course Silhouette Award Trophy |
| `CM372` | 2: Leatherette Cigar Case with Cutter; Leatherette Cigar Case with Cutter |
| `CM243*` | 2: Leatherette Hard Business Card Case; Leatherette Hard Business Card Case |
| `GM742` | 2: Light & Dark Blue Art Glass on Clear Base Large; Light & Dark Blue Art Glass on Clear Base Small |
| `CD1217` | 2: Blue Cascade Carved Acrylic Award; Red Cascade Carved Acrylic Award |
| `CM254` | 2: 6 oz Powder Coated Green Stainless Steel Lasered Flask; 6 oz. Matte Black Stainless Steel Lasered Flask |
| `CM723*` | 2: Golf Bag Tag with Tees; Leatherette Golf Bag Tag with Tees |
| `CM244*` | 2: Large Leatherette Notepad and Pen; Leatherette Notepad and Pen Small |
| `CM292` | 2: Leatherette Slim Large; Leatherette Slim Small |
| `CM727* CM737*` | 2: Polar Camel stainless steel beverage holder. Large; Polar Camel stainless steel beverage holder. Small |
| `CM711 CM712 CM713* CM726* CM731` | 2: Polar Camel Medium; Polar Camel Small |
| `CM816*` | 2: Powder Coated Tumbler Large; Powder Coated Tumbler Small |
| `CM745*` | 2: Tahoe Stemless Cocktail Tumbler; Tahoe Stemless Cocktail Tumblers |
| `CM746* CM747*` | 2: Tahoe Travel Mug; Tahoe Travel Tumbler |
| `CD549` | 1: Lucite Square & Rectangular Block Awards |

## 3. Agrupa artículos distintos de PaceSetter — separar o aceptar la agrupación (2)

| producto | detalle |
|---|---|
| Crystal Sports Tower (PS8501) | GM832G: Crystal Tower, Cheerleader | GM832E: Crystal Tower, Football | GM832Q: Crystal Tower, Wrestling | GM832B: Crystal Tower, Male Basketball | GM832L: Crystal Tower, Female Soccer | GM832J: Crystal Tower, Hockey | GM |
| Puzzle Piece Awards (PS9311) | CD1050A: Puzzle Piece, Beginning | CD1050B: Puzzle Piece, Middle | CD1050C: Puzzle Piece, End |

## 4. Mezcla ids vivos y muertos — comprobar que el producto sigue siendo lo que anuncia (16)

| producto | detalle |
|---|---|
| Bamboo Cutting Board with Handle Cutout (PS6082) | CM418=PRODUCT CM417A=PART CM417B=PART |
| Blue Steel Contoured Lucite (PS10113) | EP25PKG=ORPHAN EP25=PRODUCT |
| Bronze Resin Eagle with Crystal Tablet Trophy (PS6213) | GM549=PRODUCT RFB806=ORPHAN |
| Crimson Border Beveled Lucite (PS10294) | EP23PKG=ORPHAN EP23=PRODUCT |
| Crystal Column Sports Award with Ball (PS8464) | GM833B=PRODUCT GM833L=PRODUCT GM833M=PRODUCT GM833A=PRODUCT GM833D=PRODUCT GM833H=PRODUCT GM833J=PRODUCT GM833G=PRODUCT GM833F=ORPHAN GM833K=PRODUCT GM833C=PRODUCT GM833I=PRODUCT GM833E=PRODUCT |
| Crystal Tablet with Subsurface Star Streams Award (PS6339) | GI658=ORPHAN GI657=PRODUCT GI657B=PRODUCT |
| Gold Finish Plastic Cup on Marble Base (PS8775) | CM715AB=PART CM715AK=PART CM715AR=PART CM715BB=PART CM715BK=PART CM715BR=PRODUCT |
| Highflying Bronze Eagle with Flag and Glass Award (PS6608) | CD446=PRODUCT RFB805=ORPHAN |
| Leatherette Certificate Holder (PS10864) | CM326BL=ORPHAN CM326BS=PRODUCT CM326DB=PRODUCT CM326GR=PRODUCT CM326LB=ORPHAN CM326PK=ORPHAN CM326RS=PRODUCT CM326RW=PRODUCT |
| Leatherette Flask Gift Set (PS10874) | CM450BS=PRODUCT CM450DB=PRODUCT CM450GR=PRODUCT CM450LB=ORPHAN CM450PK=PRODUCT CM450RW=PRODUCT CM450TL=ORPHAN |
| Leatherette on Steel Shot Glass (PS10906) | CM373BS=PRODUCT CM373BV=PRODUCT CM373DB=PRODUCT CM373GR=PRODUCT CM373LB=PRODUCT CM373PK=PRODUCT CM373RV=PART CM373RW=PRODUCT CM373TL=PRODUCT |
| Phone Holder (PS9267) | PH04=PRODUCT PH07MR=ORPHAN PH13E=ORPHAN |
| Promo Glass Clear Leaf Award on Black and Clear Base (PS6862) | GM715A=PRODUCT GM715B=PART GM715C=PRODUCT |
| Soaring Excellence Gold Eagle Award Trophy (PS6962) | CBE109A=PRODUCT CBE109B=PRODUCT CBE109C=PRODUCT AE700=ORPHAN AE710=ORPHAN AE720=ORPHAN |
| Two-Tone Wood Alder Plaque (PS9538) | CD1038A=PRODUCT CD1037A=PART CD1039A=ORPHAN CD1038B=PRODUCT CD1037B=PART CD1039B=ORPHAN |
| Windermere Plaque (PS9598) | CD1093A=PRODUCT CD1094A=ORPHAN CD1093B=ORPHAN CD1094B=ORPHAN |

## 1. Sin rastro en PaceSetter — archivar o buscar el código nuevo (219)

7 tienen una pista de recodificación (un producto vivo de PaceSetter que nadie en la tienda reclama, con el mismo código base o un nombre muy parecido). Los demás: casi seguro descatalogados.

| producto | ids | pista |
|---|---|---|
| Black Crystal Triangular Votive Holder (PS9630) | G1001BK | nombre parecido: GI1001 (Crystal Triangular Votive Holder) |
| Blue Crystal Triangular Votive Holder (PS9631) | G1001BL | nombre parecido: GI1001 (Crystal Triangular Votive Holder) |
| Clear Acrylic Circle on Clear Base Award (PS10174) | DCCD943A DCCD943B DCCD943C | nombre parecido: CD943A (Clear Acrylic Circle on Clear Base) |
| Large Crystal Triangular Votive Holder (PS9638) | G1000 | nombre parecido: GI1001 (Crystal Triangular Votive Holder) |
| Leatherette Rectangle Bottle Opener Keychain (PS6720) | CM331 | mismo código: CM331BS CM331DB CM331GR CM331LB CM331PK CM331RS |
| State Shaped Frosted Acrylic Cutout on Black Plaque (PS8000) | CD953 | nombre parecido: CD955 (Frosted Acrylic Oval Cutout on Black Plaque) |
| White Crystal Triangular Votive Holder (PS9654) | G1001WH | nombre parecido: GI1001 (Crystal Triangular Votive Holder) |
| 1 Year Anniversary Service Award Freestanding (PS8013) | CD929AY1G CD929AY1S |  |
| 1-Year Anniversary Freestanding Trophy (PS8030) | CD1235AY1 CD1236AY1B CD1236AY1G CD1236AY1R |  |
| 10 Year Anniversary Freestanding Service Award (PS8014) | CD929BY10G CD929BY10S |  |
| 10-Year Anniversary Freestanding Trophy (PS8015) | CD1235BY10 CD1236BY10B CD1236BY10G CD1236BY10R |  |
| 11 oz. Black Coffee Mug (PS6007) | CM701-02 CM701-05 CM701-89 |  |
| 11 oz. Black Mug (PS6003) | CM700-05 |  |
| 11 oz. Blue Mug (PS6004) | CM700-14 |  |
| 11 oz. Green Mug (PS6005) | CM700-67 |  |
| 11 oz. Grey Mug (PS6006) | CM700-08 |  |
| 14 oz. Ceramic Mug (PS6010) | CM702-06 |  |
| 15 Year Anniversary Freestanding Award (PS8027) | CD929BY15G CD929BY15S |  |
| 15-Year Anniversary Freestanding Trophy (PS8028) | CD1235BY15 CD1236BY15B CD1236BY15G CD1236BY15R |  |
| 16 oz. Pilsner Glass (PS9621) | G0993 |  |
| 2 Year Anniversary Freestanding (PS8031) | CD929AY2G CD929AY2S |  |
| 2-Year Anniversary Freestanding Trophy (PS8041) | CD1235AY2 CD1236AY2B CD1236AY2G CD1236AY2R |  |
| 20 Year Anniversary Freestanding Award (PS8033) | CD929BY20G CD929BY20S |  |
| 20-Year Anniversary Freestanding Award Trophy (PS8034) | CD1235BY20 CD1236BY20B CD1236BY20G CD1236BY20R |  |
| 25 Year Anniversary Freestanding Award (PS8035) | CD929BY25G CD929BY25S |  |
| 25-Year Anniversary Freestanding Trophy (PS8036) | CD1235BY25 CD1236BY25B CD1236BY25G CD1236BY25R |  |
| 3 Year Anniversary Freestanding Award (PS8044) | CD929AY3G CD929AY3S |  |
| 3-Year Anniversary Freestanding Trophy (PS8057) | CD1235AY3 CD1236AY3B CD1236AY3G CD1236AY3R |  |
| 30 Year Anniversary Freestanding Award (PS8051) | CD929BY30G CD929BY30S |  |
| 30-Year Anniversary Freestanding Trophy (PS8052) | CD1235BY30 CD1236BY30B CD1236BY30G CD1236BY30R |  |
| 35 Year Anniversary Freestanding Award (PS8053) | CD929BY35G CD929BY35S |  |
| 35-Year Anniversary Freestanding Trophy (PS8054) | CD1235BY35 CD1236BY35B CD1236BY35G CD1236BY35R |  |
| 3D Etched Crystal Cube (PS6025) | GNS137 |  |
| 3D Etched Crystal Diamond Cube (PS6026) | GNS142 |  |
| 4 Year Anniversary Freestanding (PS8058) | CD929AY4G CD929AY4S |  |
| 4-Year Anniversary Freestanding Trophy (PS8063) | CD1235AY4 CD1236AY4B CD1236AY4G CD1236AY4R |  |
| 40 Year Anniversary Freestanding Award (PS8059) | CD929BY40G CD929BY40S |  |
| 40-Year Anniversary Freestanding Trophy (PS8060) | CD1235BY40 CD1236BY40B CD1236BY40G CD1236BY40R |  |
| 45 Year Anniversary Freestanding Award (PS8061) | CD929BY45G CD929BY45S |  |
| 45-Year Anniversary Freestanding Trophy (PS8062) | CD1235BY45 CD1236BY45B CD1236BY45G CD1236BY45R |  |
| 5 Year Anniversary Freestanding Award (PS8064) | CD929AY5G CD929AY5S |  |
| 5-Year Anniversary Freestanding Award (PS8067) | CD1235AY5 CD1236AY5B CD1236AY5G CD1236AY5R |  |
| 50 Year Anniversary Freestanding Award (PS8065) | CD929BY50G CD929BY50S |  |
| 50-Year Anniversary Freestanding Trophy (PS8066) | CD1235BY50 CD1236BY50B CD1236BY50G CD1236BY50R |  |
| A Frame Acrylic Phone Holder Calendar (PS8075) | PH15 |  |
| A-Frame Phone Holder (PS8084) | PH01 PH03A PH03B |  |
| Abstract Acrylic Award with Wood Accent (PS9627) | CD1254BL CD1254GR CD1254RD |  |
| Acrylic Chandelier Magnetic Stackable Bar (PS8078) | CD1210 |  |
| Add-on Compass Medallion (PS6041) | CD999MC |  |
| Add-on Globe Medallion (PS6042) | CD999MG |  |
| Add-on Logo Digi (PS6043) | CD999YD |  |
| Add-on Logo Lasered Disc (PS6044) | CD999YL |  |
| Add-on Star Medallion (PS6045) | CD999MS |  |
| Alder and Acrylic Sculpted Shape Award (PS8085) | CD1232 CD1234 CD1233 CD1231 |  |
| Alder Wood and Silver Backer Digi (PS6046) | CD971AA CD971BA |  |
| Amphitheatre Tower Award (PS6054) | CD935A CD935B |  |
| Aqua Ceramic Mug (PS8121) | CM708-755 |  |
| Aqua Wave Circle Award on Ebony Lucite Oval Base (PS6063) | CD938A CD938B |  |
| Arch Acrylic Chandelier Award Topper (PS8129) | CD1212 |  |
| Arch Horizon Acrylic Award (PS9918) | CD1200ABL CD1200AGY CD1200ARD CD1200BBL CD1200BGY CD1200BRD  |  |
| Art Glass Vases Brilliant Red Centerpiece (PS6073) | GI569 |  |
| Art Glass Vases Distinctive Glass Glazed (PS6074) | GI568 |  |
| Avondale Award Large (PS9942) | CD1102A |  |
| Avondale Award Small (PS9941) | CD1102B |  |
| Bamboo Plaque and Frost Acrylic (PS8161) | CD1092A CD1091A CD1090A CD1092B CD1091B |  |
| Beech Wood Business Card Holder (PS8166) | CM749B |  |
| Beechwood Keychain (PS6087) | CM453 |  |
| Beveled Aquus Shield Plaque (PS8169) | CDUS05A CDUS05B |  |
| Black and Clear Acrylic Beveled Perpetual Plaque (PS8197) | CD1222 |  |
| Black Ceramic Mug Large (PS8204) | CM704-05 CM707-05 CM708-05 CM706-05 |  |
| Black Ceramic Mug Small (PS8203) | CM705-05 |  |
| Black Glass Arrowhead (PS8215) | G0987A G0987B G0987C |  |
| Black Glass Pinnacle Award (PS8214) | G0999A G0999B G0999C |  |
| Blown Art Glass Egg with Black Lasered Plate (PS8226) | GM683 |  |
| Blown Glass Bud Vase (PS8227) | GNS123 |  |
| Blue Arched Brilliance Award (PS6156) | CD635B |  |
| Blue Barrel Plaque w/ Marble Mist Large (PS6159) | C031AB |  |
| Blue Barrel Plaque w/ Marble Mist Small (PS6158) | C031BB |  |
| Blue Ceramic Mug (PS8243) | CM704-632 CM707-287 CM708-04 CM706-04 |  |
| Blue Curved Wedge Crystal Award (PS8253) | GM834 |  |
| Blue Gentle Waves On Ebony Wood Base Large (PS6179) | CD322AB |  |
| Blue Leatherette Phone Easel (PS10094) | CM296BL |  |
| Blue Marble Noble Plaque (PS6188) | CD414 |  |
| Blue Rectangle Vapor Mist Award (PS6194) | CD310AB CD310BB |  |
| Blue Sail Award with Clear Panel on Mirror and Black Base (PS6195) | GI656 |  |
| Bottle Opener & Wine Corkscrew (PS10124) | CM376BS CM376BV CM376CK CM376DB CM376GR CM376LB CM376PK CM37 |  |
| Breakthrough Acrylic Star Award (PS8317) | CD1214 |  |
| Brown Ceramic Mug (PS8327) | CM705-43 |  |
| Brushed Metal Design Metallic Imprint Ring Award (PS9632) | CD1250BLG CD1250BLS CD1250GRG CD1250GRS CD1250RDG CD1250RDS |  |
| Burgundy Ceramic Mug (PS8329) | CM704-43 CM707-209 |  |
| Clear Acrylic with Aluminum Alpine Award (PS8368) | CD1104 |  |
| Clear and Black Acrylic Alpine Award (PS8369) | CD1105A CD1105B |  |
| Clear Angular Vase (PS8372) | G0989A G0989B |  |
| Clear Cascade Carved Acrylic Award (PS8377) | CD1216 |  |
| Clear Cascade Collection Lucite Award (PS6270) | C3450AC C3450BC |  |
| Clear Diamond Desk Award (PS6278) | C401 |  |
| Clear Glass BonBon Bowl with Lid (PS6279) | GI653 |  |
| Clear Glass Certificate Display (PS8402) | CM809 |  |
| Clear Millennium Lucite Tower (PS6289) | C3150A C3150B |  |
| Clipped Corner Clear Crystal Paperweight (PS8425) | GM825A GM825B GM825C |  |
| Clipped Corner Laser Edge Award (PS8426) | CD912AC CD912BC |  |
| Coral Ceramic Mug (PS8430) | CM704-3192 CM706-3192 |  |
| Cork Luggage Tag (PS6303) | CM295CK |  |
| Cork Wine Box (PS6310) | CM425CK |  |
| Crossroads Plaque (PS10297) | CD1041ABK CD1041ABL CD1041ARD CD1041BBK CD1041BBL CD1041BRD |  |
| Crystal Card Paperweight w/ Digi (PS6319) | DCGM448 |  |
| Crystal Clear Bowl (PS6322) | GI633 |  |
| Crystal Iceberg Award Large (PS6328) | GI639B |  |
| Crystal Iceberg Award Small (PS6329) | GI639A |  |
| Crystal Tablet with Digi Large (PS10335) | GI584AYD |  |
| Crystal Tablet with Digi Small (PS10334) | GI584BYD |  |
| Crystal Tablets with Digi Awards (PS6344) | DCGI540C |  |
| Crystal Tablets with Digi Color Award (PS6345) | DCGI540B |  |
| Crystal Tablets With Digi Medium (PS6346) | DCGI540A |  |
| Crystal with Sub (PS6357) | GI655 |  |
| Dark Blue Ceramic Mug (PS8520) | CM704-04 |  |
| Dark Green Ceramic Mug (PS8523) | CM704-11 |  |
| Diamond Carved Clear Desk Award (PS6368) | C512 |  |
| Diamond Carved Clear Desktop Tower (PS10416) | C403A |  |
| Diamond Carved Clear Tower (PS10418) | C403B |  |
| Diamond Carved Square Lucite Award On Marble (PS6373) | C2760A C2760B |  |
| Diamond Carved Wave Lucite Award on Marble (PS6374) | C2761B |  |
| Diamond Carved Wave Lucite Award Trophy (PS6375) | C2761A |  |
| Diamond Laser Edge Award (PS8542) | CD912AD CD912BD |  |
| Digi-color On Lucite Genuine Walnut Wood Riser Plaque (PS6379) | CD488 |  |
| Double Tower on Marble Award (PS6385) | C3050 |  |
| El Grande Green Ceramic Mug (PS8810) | CM704-1840 CM707-3415 CM708-11 |  |
| Elegant Clear Vase (PS6400) | GI634 |  |
| Floating Lucite Crescent in Bronze Resin Award (PS8600) | CD751 |  |
| Florentine Edge Plate on Walnut Finish Board (PS8613) | C071AASWF |  |
| Frosted Lucite USA Cutout on Risers Award (PS8004) | CD900USA |  |
| Full Color Free Standing Lucite Block (PS6382) | DCC0812A DCC0813A DCC0812B DCC0813B DCC0812C DCC0813C |  |
| Gatsby Plaque (PS8744) | CD1098A CD1098B |  |
| Genuine Walnut Barrel Large (PS6549) | C031AG |  |
| Genuine Walnut Barrel Plaque (PS6548) | C031BG |  |
| Genuine Walnut Certificate/Overlay Plaque (PS6550) | C3501CW |  |
| Gold Gemstone Brilliance Lucite Peak Award (PS6567) | CD603AGO CD603BGO CD603CGO |  |
| Gold Optic Crystal Gemstone Award (PS6577) | GI513AGO GI513BGO GI513CGO |  |
| Gold Tone Star Paperweight (PS6584) | C0557 |  |
| Gold Vibrant Gemstone Award (PS6585) | GI512AGO GI512BGO |  |
| Gray Art Glass Vase (PS6587) | GI585 |  |
| Green Arched Brilliance Award (PS10753) | CD635GR |  |
| Green Cascade Carved Acrylic Award (PS8808) | CD1217GN |  |
| Green Ceramic Mug Small (PS8809) | CM705-11 |  |
| Jade Glass Circle Award (PS6619) | DCGI496A DCGI496B |  |
| Jade Glass Oblong Octagon Award Trophy (PS6623) | DCGI36 |  |
| Jade Glass Octagon On Base w/ Digi (PS6624) | DCGI35 |  |
| Jade Glass Slant Peak Award Trophy (PS6627) | DCGI527A DCGI527B DCGI527C |  |
| Jade Glass Tablet Award Plaque (PS6630) | DCGM446A DCGM446B DCGM446C |  |
| Lasered Lucite on Bamboo Plaque (PS6646) | CD665A CD665B |  |
| Lavender Ceramic Mug (PS8894) | CM704-5405 |  |
| Leatherette Dome Top Clock (PS10868) | CM298DB CM298LB CM298RW |  |
| Leatherette Double Pen Case with Large (PS10870) | CM247BS |  |
| Leatherette Double Pen Case with Small (PS10869) | CM247DB CM247GR CM247LB CM247RW |  |
| Leatherette Mug Sleeve Small (PS10902) | CM287BS |  |
| Leatherette Phone Wallet Large (PS10918) | CM325BS CM325DB CM325GR CM325LB CM325PK CM325RS CM325RW |  |
| Leatherette Phone Wallet Small (PS10917) | CM325BL |  |
| Leatherette Phone Wallet With Ring (PS9063) | CM375BB |  |
| Leatherette Phone Wallet With Ring (PS10916) | CM375BS CM375DB CM375GR CM375LB CM375PK CM375RS CM375RW CM37 |  |
| Light Blue Ceramic Mug (PS9147) | CM708-632 |  |
| Lucite Magnetic Entrapment with Steel Back (PS6750) | CD883A CD883B |  |
| Lucite Plaque w/ Hanger/Easel (PS6752) | CD420 |  |
| Mahogany Wood and Silver Backer Digi (PS6757) | CD971AM CD971BM |  |
| Maroon Ceramic Mug (PS9166) | CM708-43 |  |
| Mustard Ceramic Mug (PS9187) | CM704-640 |  |
| Optic Crystal Circle with Silver Star (PS9208) | GM626A GM626B |  |
| Optic Crystal Diamond on Base (PS9643) | GI847 |  |
| Orange Ceramic Mug (PS9252) | CM707-2956 CM708-2956 |  |
| Pink Leatherette Phone Easel (PS11149) | CM296PK |  |
| Pivoting Bars Award Stacker Bar (PS9276) | CD1220B CD1220C CD1220W |  |
| Pivoting Bars Award with Magnetic Base (PS9277) | CD1221B CD1221C CD1221W |  |
| Plum Ceramic Mug (PS9279) | CM704-1041 |  |
| Purple Ceramic Mug Large (PS9307) | CM704-2899 CM707-84 CM708-25 |  |
| Purple Ceramic Mug Small (PS9306) | CM705-04 |  |
| Puzzle Crescent on Base (PS9310) | GI675 |  |
| Red Arched Brilliance Award (PS6875) | CD635R |  |
| Red Barrel Plaque w/ Marble Mist Small (PS6876) | C031BR |  |
| Red Barrel Plaque with Marble Mist Plate (PS6877) | C031AR |  |
| Red Ceramic Mug (PS9331) | CM704-2904 CM707-199 CM708-3192 CM706-664 |  |
| Red Rectangle Vapor Mist Award (PS6889) | CD310AR CD310BR |  |
| Rose Leatherette Phone Easel (PS11252) | CM296RS |  |
| Rosewood Piano Finish Lucite Barrel Riser Plaque Large (PS11266) | CM266ARW |  |
| Rosewood Piano Finish Lucite Barrel Riser Plaque Small (PS11265) | CM266BRW |  |
| Rosewood Piano Finish Star Paperweight (PS6907) | C0627 |  |
| Round Acrylic Coaster (PS9374) | CD1107A |  |
| Round Alder Coaster (PS9375) | CD1106A |  |
| Round Bamboo Wine Gift Set (PS6913) | CM423 |  |
| Russet Ceramic Mug (PS9382) | CM704-464 |  |
| Sapphire Blue Accent Diamond On Marble Award (PS6918) | CD763A CD763B |  |
| Sheared Lucite Cylinder Award Trophy (PS6919) | C2613 |  |
| Silver Plated Cup on Base (PS9407) | CM812 |  |
| Slant-Front Crystal Award with Compass Medallion (PS6943) | GI582MC |  |
| Slant-Front Crystal Award with Globe Medallion (PS6945) | GI582MG |  |
| Slant-Front Crystal Award with Star Medallion (PS6947) | GI582MS |  |
| Slant-Front Crystal with Lasered Black Disc Logo Medallion (PS6946) | GI582YL |  |
| Slant-Front Crystal with Logo Medallion (PS6944) | GI582YD |  |
| Spotlight Lucite & Aluminum Lucite Base (PS6963) | C4952 C4953 |  |
| Square Acrylic Coaster (PS9468) | CD1107B |  |
| Square Alder Coaster (PS9469) | CD1106B |  |
| Star Gazer Acrylic Award Trophy (PS11378) | CD919AG CD919AS CD919BG CD919BS |  |
| Star Gold Trophy On Rosewood Piano (PS6966) | C0632 |  |
| Star Stream Silver Lucite On Base (PS6967) | CD859A CD859B CD859C |  |
| Stars Silver Trophy On Rosewood Piano (PS6969) | C0633 |  |
| Streaming Excellence on Marble Award (PS11394) | CD1006BL CD1006GR CD1006RD |  |
| Sweeping Cosmos Acrylic with Mirror Accent (PS11397) | CD970RD |  |
| Teal Ceramic Mug (PS9515) | CM704-755 CM708-579 CM706-755 |  |
| The Noir Glass Dome On Black Glass Base (PS11429) | GI306 |  |
| Turquoise and Stone Lucite 13 Plate Photo Plaque (PS11442) | EP20PKG |  |
| Vivid Spiral Acrylic on Marble (PS9541) | CD1007BRD |  |
| Vivid Spiral Acrylic on Marble (PS11490) | CD1007ARD CD1007ABL CD1007BBL |  |
| Walnut Business Card Holder (PS9542) | CM749W |  |
| Walnut Plaque with Gold Swirl Plate (PS7016) | CD662A CD662B |  |
| Water Bottle Carabiner Large (PS11523) | CM822GR CM822OR CM822PU CM822RD |  |
| Water Bottle Carabiner Small (PS11522) | CM822BL |  |
| Water Drop Award (PS11524) | CD1017ARD CD1017BRD CD1017ABL CD1017BBL CD1017CRD CD1017CBL |  |
| Waterfall Crystal on Base (PS9586) | GM752A GM752B GM752C |  |
| White Ceramic Mug (PS9588) | CM707-02 |  |
| White Fluted Vase (PS7021) | GI636W |  |
| Yellow Ceramic Mug (PS9605) | CM708-640 |  |
