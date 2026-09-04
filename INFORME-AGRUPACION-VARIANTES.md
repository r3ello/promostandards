# Informe: agrupación de variantes de PaceSetter

**Fecha:** 22 de julio de 2026
**Asunto:** Por qué no es posible agrupar automáticamente los productos que son "variantes"
(tallas / tamaños) de un mismo producto.
**Fuente:** consultas en vivo contra el servicio **PromoStandards Product Data 1.0.0** de PaceSetter
(`ProductDataService`, operación `getProduct`, entorno de producción).

---

## 1. Resumen ejecutivo

Se solicitó agrupar en una sola ficha los productos que en realidad son el **mismo artículo en
distintos tamaños** (p. ej. Small / Medium / Large), que hoy aparecen como productos independientes.

**Conclusión:** la agrupación automática **no es posible de forma fiable** porque PaceSetter **no
entrega el dato que indica qué productos forman una familia**. El estándar PromoStandards sí define
un mecanismo para ello (el campo *Common Grouping*), pero **PaceSetter lo devuelve vacío en todos los
productos verificados**, incluidos los casos donde la relación es obvia.

Sin ese dato, cualquier agrupación sería una **suposición** basada en el código o el nombre del
producto, que —como se muestra abajo— **no es confiable** y produciría agrupaciones incorrectas.

---

## 2. Cómo debería funcionar la agrupación (según el estándar)

PromoStandards Product Data define, dentro de la respuesta de cada producto (`getProduct`), un arreglo
de **productos relacionados** (`RelatedProduct`). Cada relación lleva un tipo (`relationType`) que
puede ser:

| relationType       | Significado                                                        |
|--------------------|--------------------------------------------------------------------|
| `Substitute`       | Producto sustituto                                                 |
| `Companion Sell`   | Producto complementario (venta cruzada)                            |
| **`Common Grouping`** | **Productos que pertenecen a la misma familia (p. ej. tallas)** |

El tipo **`Common Grouping`** es, precisamente, el que el proveedor debe usar para decir
*"estos códigos son el mismo producto en distintos tamaños"*. Si PaceSetter lo poblara, la
agrupación sería automática y exacta.

---

## 3. Hallazgo: PaceSetter devuelve el dato vacío

Al consultar productos que **claramente son variantes de tamaño del mismo artículo**, el campo de
productos relacionados (`relatedProducts` / `RelatedProduct`) llega **vacío** en todos los casos.

### Ejemplos concretos (datos reales, 22-jul-2026)

| Código  | Nombre del producto (PaceSetter)                          | Productos relacionados |
|---------|------------------------------------------------------------|:----------------------:|
| CBE109A | Gold Antique Finish Resin Cast Eagle **- Small**           | **vacío**              |
| CBE109B | Gold Antique Finish Resin Cast Eagle **- Medium**          | **vacío**              |
| CBE109C | Gold Antique Finish Resin Cast Eagle **- Large**           | **vacío**              |
| C073A   | Black with Gold Florentine on Notched Corner Genuine Walnut | **vacío**             |
| C073B   | Black with Gold Florentine on Notched Corner Genuine Walnut | **vacío**             |
| C0611   | Large Blue Dynasty Award                                    | **vacío**              |
| C0612   | Enterprise Large Blue Dynasty Award                         | **vacío**              |
| C0613   | Small Blue Dynasty Award with Clear Lucite Base            | **vacío**              |
| C0614   | Large Blue Dynasty Award with Clear Lucite Base           | **vacío**              |

`CBE109A/B/C` es el ejemplo más claro: son literalmente **Small / Medium / Large del mismo águila**,
y aun así PaceSetter **no declara ninguna relación entre ellos**.

### Respuesta cruda (extracto) de `getProduct` para `CBE109A`

```json
{
  "productId": "CBE109A",
  "productName": "Gold Antique Finish Resin Cast Eagle - Small",
  "relatedProducts": []
}
```

> `"relatedProducts": []` = **arreglo vacío**. No hay ningún `Common Grouping` (ni de ningún otro
> tipo) que conecte `CBE109A` con `CBE109B` o `CBE109C`.

---

## 4. Por qué no se puede suplir con una "adivinanza"

Al no venir el dato del proveedor, la única alternativa sería **inferir** las familias a partir del
código o del nombre. Ambas vías son **poco fiables** y generarían errores:

**a) Por código de producto.** Algunas familias siguen un patrón (`CBE109` + A/B/C), pero muchas no.
Por ejemplo, `C0611`, `C0612`, `C0613`, `C0614` comparten un prefijo numérico, pero **no son
claramente el mismo producto**: sus nombres difieren en algo más que el tamaño ("Enterprise",
"with Clear Lucite Base"…). Agruparlos automáticamente sería incorrecto.

**b) Por nombre de producto.** Los nombres no comparten una raíz consistente:

- `CBE109A/B/C` → sí comparten base (`Gold Antique Finish Resin Cast Eagle`) con el tamaño al final.
- `C0611` = *Large Blue Dynasty Award* vs `C0612` = *Enterprise Large Blue Dynasty Award* → el nombre
  cambia en más que el tamaño.
- `C073A` y `C073B` → tienen **el mismo nombre exacto**, sin ninguna talla que los distinga.

Es decir, no existe una regla única que funcione para todo el catálogo (1.278 productos) sin producir
falsos positivos (agrupar cosas distintas) o falsos negativos (dejar sin agrupar familias reales).
Una agrupación basada en suposiciones **degradaría la calidad de los datos** en lugar de mejorarla.

---

## 5. Conclusión y recomendación

- La agrupación de variantes **depende de un dato que solo el proveedor puede entregar**
  (`Common Grouping` en PromoStandards).
- **PaceSetter no lo entrega** (viene vacío en todo el catálogo verificado), por lo que **no es
  técnicamente posible agrupar de forma automática y confiable** con la información disponible.
- Las alternativas por código o por nombre **no son fiables** y se descartan para no introducir
  errores en el catálogo.

**Camino a seguir sugerido (requiere acción del proveedor o del negocio):**

1. **Solicitar a PaceSetter** que poblé el campo `Common Grouping` (`RelatedProduct`) en su servicio
   Product Data. Es lo correcto según el estándar y habilitaría la agrupación automática y exacta,
   sin cambios adicionales de nuestra parte.
2. **Alternativa manual:** si el proveedor no puede hacerlo, se podría mantener un **mapeo manual**
   de familias (una lista de "estos códigos = un producto") curada por el equipo. Es exacto pero
   requiere mantenimiento continuo a medida que cambia el catálogo.

En cuanto PaceSetter entregue el dato, la funcionalidad de agrupado ya está preparada en el sistema y
se activaría sin desarrollo adicional.
