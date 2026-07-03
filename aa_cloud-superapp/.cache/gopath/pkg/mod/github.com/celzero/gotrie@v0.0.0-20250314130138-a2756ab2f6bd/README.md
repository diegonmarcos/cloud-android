# gotrie

## Build FrozenTrie
```
  err,FT := trie.Build("./td.txt","./rd.txt","./basicconfig.json","./filetag.json")
```  
## Create URLencoded Base64 string
```
  param := []string{"AMI","CQT","EOK","MTF"}		
  
  usr_flag := FT.CreateUrlEncodedflag(param)         ######   input 3character unique string of blacklist file name
  
  fmt.Println(usr_flag)
  
  #########  following will be output as URL encoded base64 string
  
  6IeA6ICA4oCAxIAE
```
  
##  Unique filename frm User flag
```
  fmt.Println(FT.Urlenc_to_flag("6IeA6ICA4oCAxIAE"))      ###### input URLencoded base64 user flag string
  
  #########  following will be output as list of string
  
  [AMI CQT EOK MTF]
```  
  
## Domain Name lookup in Trie
```
  text := "alltereg0.ru"
  
  usr_flag := "6IeA6ICA4oCAxIAE"
  
  FT.DNlookup(text,usr_flag)
  
  #########  following will be output as (boolean, list of string)
    
  true [EOK AMI]
  
  text := "google.com"
  
  usr_flag := "6IeA6ICA4oCAxIAE"
  
  FT.DNlookup(text,usr_flag)
  
  #########  following will be output as (boolean, list of string)
  
  false []
```
