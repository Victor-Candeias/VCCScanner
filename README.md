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
# A mesma suite contra o build release minificado por R8:
.\gradlew.bat -PvccTestBuildType=release connectedAndroidTest
```

APK de desenvolvimento: `app/build/outputs/apk/debug/app-debug.apk`.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O APK debug é assinado automaticamente para testes.

## Publicação

O build `release` usa R8 com `minifyEnabled` e `shrinkResources`; as regras de manutenção estão em
`app/proguard-rules.pro` e cobrem o `RoomDatabase` gerado, as entidades, os detetores do ML Kit e os
`CREATOR` dos parcelables devolvidos pelo scanner.

A versão é definida por `vccVersionName` (`MAJOR.MINOR.PATCH`, cada parte abaixo de 100) e o
`versionCode` é derivado dela como `MAJOR*10000 + MINOR*100 + PATCH`, para nunca recuar nem repetir
um código já publicado:

```powershell
.\gradlew.bat bundleRelease -PvccVersionName=1.1.0   # versionCode 10100
```

Nenhuma chave privada está no repositório. As credenciais de assinatura são lidas de
`local.properties` (ignorado pelo git) ou das variáveis de ambiente equivalentes; sem elas o build
release é produzido sem assinatura.

| `local.properties` | Variável de ambiente |
| --- | --- |
| `vcc.keystore` | `VCC_KEYSTORE` |
| `vcc.keystore.password` | `VCC_KEYSTORE_PASSWORD` |
| `vcc.key.alias` | `VCC_KEY_ALIAS` |
| `vcc.key.password` | `VCC_KEY_PASSWORD` |

O App Bundle assinado fica em `app/build/outputs/bundle/release/app-release.aab`.

`-PvccTestBuildType=release` corre a suite instrumentada contra esse build minificado. Nessa
execução são acrescentadas as regras de `app/proguard-instrumentation-rules.pro`, que mantêm o que
só o harness de testes alcança (`pt.vcc.scanner.**`, `androidx.room.**`, kotlin-stdlib e
`androidx.tracing.Trace`); o APK publicado continua a usar apenas `app/proguard-rules.pro`.

`.github/workflows/android.yml` corre `test`, `lint` e `assembleDebug` em cada push e pull request,
e produz o `.aab` assinado nas tags `v*`, a partir dos segredos `VCC_KEYSTORE_BASE64`,
`VCC_KEYSTORE_PASSWORD`, `VCC_KEY_ALIAS` e `VCC_KEY_PASSWORD`.

Para a ficha da Play Store: [`Docs/PRIVACIDADE.md`](Docs/PRIVACIDADE.md) e
[`Docs/PLAY-DATA-SAFETY.md`](Docs/PLAY-DATA-SAFETY.md). O ícone do launcher é gerado a partir de
`Docs/VCCScannere.png` por `Docs/generate-launcher-icons.py`.

## Fluxo implementado

1. **Arquivo:** documentos recentes em cartões com miniatura da primeira página, pesquisa indexada por palavras/prefixos no nome, texto OCR, empresa, NIF, data, total, códigos e etiquetas. A pesquisa ignora acentos e maiúsculas. Categorias com o número de documentos de cada uma e filtros por tipo, empresa, intervalo de datas, intervalo de valores e etiquetas.
2. **Entrada:** scanner com câmara, importação múltipla de imagens, PDF ou seleção de ficheiros compatíveis pelo seletor Android, sem permissões gerais de acesso ao armazenamento.
3. **Captura:** scanner Google ML Kit com deteção de documentos, captura automática, recorte, correção de perspetiva e limpeza/filtros disponíveis no scanner.
4. **Edição:** editor de página dedicado, aberto a partir do documento, com pré-visualização grande, rodar, recortar, tira de filtros e ordem da página. Inclui correção automática na importação (deteção de contornos, correção de perspetiva, remoção de sombras e melhoria de contraste), recorte manual de quatro cantos com os limites já sugeridos e os filtros Automático, Original, Documento, Preto e branco, Escala de cinzentos e Foto. O OCR é atualizado após cada edição.
5. **Páginas:** adicionar, reordenar arrastando um cartão para a posição pretendida ou com os botões mover para cima/baixo do editor, e eliminar com confirmação. A opção Original recupera a página importada inicialmente, antes da correção automática.
6. **Processamento:** OCR latino local, identificação do idioma, leitura de QR/códigos de barras, classificação por regras e extração de empresa, NIF, data e total. Os campos são reavaliados sempre que as páginas mudam (importação, recorte, filtros, rotação, reordenação ou eliminação); as correções feitas à mão deixam de ser sobrepostas e um valor já conhecido nunca é apagado por um OCR pior.
7. **Resultado:** imagem, texto selecionável com pesquisa dentro do documento que realça as ocorrências e conta-as, copiar texto, metadados corrigíveis e etiquetas.
8. **Saída:** PDF com imagem e camada de texto OCR, TXT UTF-8, imagens JPG ou PNG num ZIP, partilha por aplicações instaladas e impressão Android. A exportação usa o seletor de destino do sistema.
9. **Persistência:** base de dados Room/SQLite com índice FTS4, imagens no armazenamento privado e exportações temporárias em cache. Não exige conta nem servidor próprio.

## Arquitetura

- `MainActivity.java`: navegação (arquivo, documento e editor de página), biblioteca, edição dos dados, seletores de ficheiros, scanner, partilha e impressão. As miniaturas da lista são descodificadas fora da thread da interface e guardadas numa cache limitada às 60 últimas páginas.
- `DocumentEngine.java`: importação sequencial, processamento de imagem, OCR/códigos/idioma, recorte e exportações. As operações demoradas são executadas fora da thread da interface. Depois de cada alteração guardada, `sweep` elimina as imagens que deixaram de ser referenciadas — as substituídas por rotações, filtros e recortes, as das páginas eliminadas e as deixadas por operações interrompidas — preservando a página atual e o respetivo original.
- `CropView.java`: seleção dos quatro cantos, iniciados nos limites detetados automaticamente quando existem.
- `ImageProcessor.java`: correção automática das páginas. Deteta o contorno do documento por limiar de Otsu e componentes ligadas, reduz o maior lado a 420 píxeis para a análise, valida o quadrilátero (convexo, no centro da imagem e com ângulos próximos de 90°) e corrige a perspetiva. A remoção de sombras estima o nível do papel numa grelha de células, divide cada píxel por esse nível interpolado e estica o resultado entre o preto e o branco, mantendo a cor através de um ganho comum aos três canais. Uma imagem já plana e bem iluminada é deixada intacta.
- `res/values/strings.xml`: todo o texto visível, incluindo plurais de páginas e documentos e as descrições para leitores de ecrã. As categorias guardadas na base de dados e as ações de página (`DocumentEngine.ACTION_*`) são chaves estáveis independentes das etiquetas traduzíveis, para que uma tradução não altere dados nem comportamento.
- `Document`, `DocumentIndex`, `DocumentDao`, `ScannerDatabase`: persistência e atualização transacional do índice de pesquisa [FTS4](https://www.sqlite.org/fts3.html). Inclui migração da versão inicial sem índice para a versão 2 e da versão 2 para a versão 3, que regista os campos corrigidos pelo utilizador.
- `Metadata.java`: classificação, extração e normalização de pesquisa, com testes unitários. `Metadata.edit` marca um campo como corrigido à mão e `Metadata.extract` respeita essa marca. `Metadata.day` e `Metadata.cents` leem datas e valores para números comparáveis, suportando os filtros por intervalo do arquivo.

## Limites e utilização

- O scanner de câmara depende de Google Play Services e pode precisar de Internet para descarregar os componentes na primeira utilização. A importação e os modelos de OCR/códigos/idioma estão incluídos na aplicação. Consulte a [documentação do scanner ML Kit](https://developers.google.com/ml-kit/vision/doc-scanner/android) e do [reconhecimento de texto](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).
- A qualidade do OCR depende da imagem; a classificação e os campos extraídos são sugestões por regras, não validação fiscal. Os valores devem ser revistos pelo utilizador.
- Limite de 50 páginas por documento. Imagens normalizadas até 2200 píxeis no maior lado. PDFs importados são rasterizados e passam novamente por OCR; assinaturas, formulários e estrutura vetorial do original não são preservados.
- A correção automática precisa que o documento seja mais claro do que a superfície por baixo e que ocupe pelo menos 15% da imagem. Quando não reconhece um contorno fiável, a página é importada tal como está e o recorte manual continua disponível.
- Ficheiros suportados: imagens decodificáveis pelo Android e PDFs sem palavra-passe. Não importa ficheiros Office.
- Os filtros por intervalo comparam a data e o total extraídos pelo OCR: um documento sem esse campo reconhecido não entra no intervalo. As datas aceitam `dd/mm/aaaa`, `mm/aaaa` ou `aaaa` e os valores aceitam vírgula ou ponto decimal. O idioma identificado não traduz o documento.
- O PDF guarda texto OCR sob a imagem opaca, mantendo o aspeto do documento e permitindo pesquisa/seleção. A leitura depende também do visualizador PDF.
- Não há sincronização, encriptação adicional da base de dados, bloqueio biométrico nem serviço de processamento em segundo plano. Mantenha a aplicação aberta durante uma importação longa. O backup automático está desativado; exporte os documentos que pretende conservar antes de desinstalar.
- A captura real com câmara, a qualidade em documentos físicos e a impressão numa impressora requerem validação no equipamento de destino.
- A interface está apenas em português: `strings.xml` permite traduzir, mas não existem ainda ficheiros `values-<idioma>`. As datas usam sempre o formato pt-PT.
- Acessibilidade: os elementos interativos têm descrição, as etiquetas de categoria e os filtros de página são anunciados como botões selecionáveis com área mínima de 48 dp e o texto secundário cumpre o contraste WCAG AA. A reordenação por arrasto tem sempre alternativa por botões no editor de página. Falta validar uma passagem completa com o TalkBack num dispositivo real.

## Testes

Os testes unitários cobrem extração de fatura portuguesa, distinção entre subtotal e total, campos ausentes, categorias, NIF inválido, pesquisa sem acentos, construção das consultas por prefixo e a leitura de datas e valores para os filtros por intervalo.

O teste instrumentado cria uma fatura artificial e exercita importação, OCR, índice Room, recorte, filtros, rotação, reposição do original, exportação PDF/TXT/JPG/PNG e reimportação PDF. Em Android 15+ também verifica o texto extraído da camada pesquisável do PDF. Testes separados verificam o tratamento de uma imagem inexistente, a persistência após reabrir a base de dados, incluindo atualização/eliminação do índice, a limpeza das imagens substituídas por edições repetidas e pela eliminação de páginas, e a correção automática de uma fotografia sintética em perspetiva e com sombra — deteção do contorno, reposição do formato da folha, achatamento da iluminação e OCR completo — garantindo também que um digitalizado plano não é alterado.

`ScreenFlowTest` percorre a interface real: abre o documento a partir do cartão do arquivo, pesquisa dentro do texto OCR (com e sem ocorrências), abre o editor de página, aplica um filtro, reordena as páginas e confirma cada resultado na base de dados antes de voltar ao arquivo.
