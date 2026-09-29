# Segurança dos dados (Google Play) — VCC Scanner

Respostas a dar no formulário **Data safety** da Google Play Console. Baseiam-se no comportamento
verificável da aplicação: não existe código de rede, não existem permissões declaradas em
`app/src/main/AndroidManifest.xml` e a cópia de segurança automática está desativada.

## Recolha e partilha

| Pergunta | Resposta |
| --- | --- |
| A aplicação recolhe ou partilha algum dos tipos de dados exigidos? | **Não** |
| Todos os dados do utilizador são encriptados em trânsito? | Não aplicável — a aplicação não transmite dados |
| Existe forma de o utilizador pedir a eliminação dos dados? | **Sim** — eliminação no próprio dispositivo (eliminar documento ou desinstalar) |

Justificação: os documentos, o texto reconhecido e os metadados ficam no armazenamento privado da
aplicação. Nada é enviado para servidores do programador nem para terceiros. Uma exportação, uma
partilha ou uma impressão é sempre iniciada pelo utilizador, com destino escolhido no seletor do
sistema, e por isso não conta como recolha nem partilha pela aplicação.

## Tipos de dados — confirmação

| Tipo de dados | Recolhido | Partilhado | Nota |
| --- | --- | --- | --- |
| Ficheiros e documentos | Não | Não | Processados e guardados apenas no dispositivo |
| Fotografias e vídeos | Não | Não | As imagens importadas ou capturadas ficam no dispositivo |
| Informações pessoais (nome, NIF, e-mail) | Não | Não | Extraídas do documento pelo OCR local e guardadas no dispositivo |
| Informações financeiras | Não | Não | O total extraído de uma fatura não sai do dispositivo |
| Localização | Não | Não | Não é lida |
| Identificadores do dispositivo ou de anúncios | Não | Não | Não são lidos |
| Atividade da aplicação, diagnósticos, falhas | Não | Não | Não existe analítica nem relatório de falhas |

## Práticas de segurança

- **Encriptação em trânsito:** não aplicável, a aplicação não faz pedidos de rede.
- **Dados em repouso:** guardados no armazenamento privado da aplicação, protegido pelo sandbox do
  Android e pela encriptação do dispositivo. A aplicação não acrescenta encriptação própria à base
  de dados.
- **Eliminação:** o utilizador elimina documentos na aplicação; desinstalar apaga tudo.
- **Cópia de segurança:** desativada (`android:allowBackup="false"`), pelo que os documentos não são
  copiados para a cloud pelo sistema.
- **Revisão independente de segurança:** não foi realizada.

## Componentes de terceiros

| Componente | Função | Dados |
| --- | --- | --- |
| ML Kit (reconhecimento de texto, códigos de barras, identificação de idioma) | Incluído na aplicação, executa localmente | Não transmite imagens nem texto |
| Scanner de documentos do Google Play Services | Captura com a câmara | Módulo descarregado do Google Play na primeira utilização; a captura é processada no dispositivo. Rege-se pela [Política de Privacidade do Google](https://policies.google.com/privacy) |
| AndroidX / Room | Interface e base de dados local | Sem rede |

## Ligação a publicar

Na ficha da Play Store, indicar como política de privacidade o ficheiro
[`Docs/PRIVACIDADE.md`](PRIVACIDADE.md) publicado num URL estável (por exemplo, a versão em
`raw.githubusercontent.com` ou uma página do GitHub Pages do repositório).
