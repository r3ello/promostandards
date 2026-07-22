<#
  Lists sellable product IDs from PaceSetter Awards via the PromoStandards
  Product Data 1.0.0 getProductSellable operation. Use any returned ID as the
  {productId} for the Inventory live test (GET /api/inventory/{productId}/levels).

  This is a throwaway bootstrap call (raw SOAP) so you don't need a full Product
  Data client just to discover a product id.

  Usage (from the project root):
    powershell -ExecutionPolicy Bypass -File scripts\get-pacesetter-product-ids.ps1 -Id <ID> -Password <PASSWORD>

  Optional: -Max <n> to change how many ids are printed (default 30).
#>
param(
  [Parameter(Mandatory = $true)][string]$Id,
  [Parameter(Mandatory = $true)][string]$Password,
  [int]$Max = 30
)

$endpoint = 'https://pacesetterawards.com/ProductDataService/PaceSetterProductDataWCF.svc'
$ns   = 'http://www.promostandards.org/WSDL/ProductDataService/1.0.0/'
$shar = 'http://www.promostandards.org/WSDL/ProductDataService/1.0.0/SharedObjects/'

$body = @"
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="$ns" xmlns:shar="$shar">
  <soapenv:Header/>
  <soapenv:Body>
    <ns:GetProductSellableRequest>
      <shar:wsVersion>1.0.0</shar:wsVersion>
      <shar:id>$Id</shar:id>
      <shar:password>$Password</shar:password>
      <shar:isSellable>true</shar:isSellable>
    </ns:GetProductSellableRequest>
  </soapenv:Body>
</soapenv:Envelope>
"@

try {
  $resp = Invoke-WebRequest -Uri $endpoint -Method Post `
    -ContentType 'text/xml; charset=utf-8' `
    -Headers @{ SOAPAction = '"getProductSellable"' } `
    -Body $body -TimeoutSec 120
  $xml = [xml]$resp.Content
}
catch {
  Write-Output "HTTP/SOAP call failed: $($_.Exception.Message)"
  if ($_.Exception.Response) {
    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
    Write-Output "--- response body ---"
    Write-Output $reader.ReadToEnd()
  }
  exit 1
}

$nsm = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
$nsm.AddNamespace('shar', $shar)

$fault = $xml.SelectNodes('//*[local-name()="faultstring"]') | ForEach-Object { $_.InnerText }
$err   = $xml.SelectNodes('//shar:ErrorMessage', $nsm)        | ForEach-Object { $_.InnerText }
if ($fault) { Write-Output "SOAP fault: $fault" }
if ($err)   { Write-Output "ErrorMessage: $err" }

$ids = $xml.SelectNodes('//shar:productId', $nsm) | ForEach-Object { $_.InnerText } | Select-Object -Unique
Write-Output "Sellable product IDs returned: $($ids.Count)"
$ids | Select-Object -First $Max
