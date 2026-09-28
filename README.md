# VCC Scanner

Aplicação Android nativa em português, baseada no fluxo em `Docs/Imagem ChatGPT 25_09_2026, 22_40_34.png`.

## Executar

Abra esta pasta no Android Studio e sincronize o Gradle. Requer JDK 17 ou superior, Android SDK 37 e um dispositivo Android 9 (API 28) ou superior.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug test lint
# Com emulador ou telemóvel ligado por ADB:
.\gradlew.bat connectedDebugAndroidTest
```

APK de desenvolvimento: `app/build/outputs/apk/debug/app-debug.apk`.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O APK debug é assinado automaticamente para testes. A publicação exige uma chave de assinatura própria e configuração de release; nenhuma chave privada foi incluída.

## Fluxo implementado

1. **Arquivo:** documentos recentes, pesquisa indexada por palavras/prefixos no nome, texto OCR, empresa, NIF, data, total, códigos e etiquetas. A pesquisa ignora acentos e maiúsculas. Categorias e filtros por empresa, data, valor e etiquetas.
2. **Entrada:** scanner com câmara, importação múltipla de imagens, PDF ou seleção de ficheiros compatíveis pelo seletor Android, sem permissões gerais de acesso ao armazenamento.
3. **Captura:** scanner Google ML Kit com deteção de documentos, captura automática, recorte, correção de perspetiva e limpeza/filtros disponíveis no scanner.
4. **Edição:** recorte manual de quatro cantos com correção de perspetiva, rotação, original, cinzentos, preto e branco e contraste de documento. O OCR é atualizado após cada edição.
5. **Páginas:** adicionar, mover para cima/baixo e eliminar com confirmação. A opção Original recupera a página importada inicialmente.
6. **Processamento:** OCR latino local, identificação do idioma, leitura de QR/códigos de barras, classificação por regras e extração de empresa, NIF, data e total. Os campos são reavaliados sempre que as páginas mudam (importação, recorte, filtros, rotação, reordenação ou eliminação); as correções feitas à mão deixam de ser sobrepostas e um valor já conhecido nunca é apagado por um OCR pior.
7. **Resultado:** imagem, texto selecionável, copiar texto, metadados corrigíveis e etiquetas.
8. **Saída:** PDF com imagem e camada de texto OCR, TXT UTF-8, imagens JPG ou PNG num ZIP, partilha por aplicações instaladas e impressão Android. A exportação usa o seletor de destino do sistema.
9. **Persistência:** base de dados Room/SQLite com índice FTS4, imagens no armazenamento privado e exportações temporárias em cache. Não exige conta nem servidor próprio.

## Arquitetura

- `MainActivity.java`: navegação, biblioteca, edição dos dados, seletores de ficheiros, scanner, partilha e impressão.
- `DocumentEngine.java`: importação sequencial, processamento de imagem, OCR/códigos/idioma, recorte e exportações. As operações demoradas são executadas fora da thread da interface. Depois de cada alteração guardada, `sweep` elimina as imagens que deixaram de ser referenciadas — as substituídas por rotações, filtros e recortes, as das páginas eliminadas e as deixadas por operações interrompidas — preservando a página atual e o respetivo original.
- `CropView.java`: seleção dos quatro cantos.
- `Document`, `DocumentIndex`, `DocumentDao`, `ScannerDatabase`: persistência e atualização transacional do índice de pesquisa [FTS4](https://www.sqlite.org/fts3.html). Inclui migração da versão inicial sem índice para a versão 2 e da versão 2 para a versão 3, que regista os campos corrigidos pelo utilizador.
- `Metadata.java`: classificação, extração e normalização de pesquisa, com testes unitários. `Metadata.edit` marca um campo como corrigido à mão e `Metadata.extract` respeita essa marca.

## Limites e utilização

- O scanner de câmara depende de Google Play Services e pode precisar de Internet para descarregar os componentes na primeira utilização. A importação e os modelos de OCR/códigos/idioma estão incluídos na aplicação. Consulte a [documentação do scanner ML Kit](https://developers.google.com/ml-kit/vision/doc-scanner/android) e do [reconhecimento de texto](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).
- A qualidade do OCR depende da imagem; a classificação e os campos extraídos são sugestões por regras, não validação fiscal. Os valores devem ser revistos pelo utilizador.
- Limite de 50 páginas por documento. Imagens normalizadas até 2200 píxeis no maior lado. PDFs importados são rasterizados e passam novamente por OCR; assinaturas, formulários e estrutura vetorial do original não são preservados.
- Ficheiros suportados: imagens decodificáveis pelo Android e PDFs sem palavra-passe. Não importa ficheiros Office.
- Os filtros de data/valor são correspondências textuais, não intervalos numéricos. O idioma identificado não traduz o documento.
- O PDF guarda texto OCR sob a imagem opaca, mantendo o aspeto do documento e permitindo pesquisa/seleção. A leitura depende também do visualizador PDF.
- Não há sincronização, encriptação adicional da base de dados, bloqueio biométrico nem serviço de processamento em segundo plano. Mantenha a aplicação aberta durante uma importação longa. O backup automático está desativado; exporte os documentos que pretende conservar antes de desinstalar.
- A captura real com câmara, a qualidade em documentos físicos e a impressão numa impressora requerem validação no equipamento de destino.

## Testes

Os testes unitários cobrem extração de fatura portuguesa, distinção entre subtotal e total, campos ausentes, categorias, NIF inválido, pesquisa sem acentos e construção das consultas por prefixo.

O teste instrumentado cria uma fatura artificial e exercita importação, OCR, índice Room, recorte, filtros, rotação, reposição do original, exportação PDF/TXT/JPG/PNG e reimportação PDF. Em Android 15+ também verifica o texto extraído da camada pesquisável do PDF. Testes separados verificam o tratamento de uma imagem inexistente, a persistência após reabrir a base de dados, incluindo atualização/eliminação do índice, e a limpeza das imagens substituídas por edições repetidas e pela eliminação de páginas.
