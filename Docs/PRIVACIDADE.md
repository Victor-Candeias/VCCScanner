# Política de Privacidade — VCC Scanner

Última atualização: 29 de setembro de 2026

A VCC Scanner é uma aplicação de digitalização de documentos que funciona inteiramente no
dispositivo. Esta política descreve o que a aplicação faz com a informação que o utilizador lhe
confia.

## Responsável pelo tratamento

O responsável pelo tratamento é o titular do dispositivo. A aplicação não tem conta, não tem
servidor e a equipa de desenvolvimento não tem qualquer forma de aceder aos documentos.

## Que dados são recolhidos

Nenhuns dados são recolhidos, transmitidos ou partilhados pela aplicação.

Tudo o que o utilizador digitaliza ou importa — imagens das páginas, texto reconhecido por OCR,
códigos QR e de barras, nome, categoria, empresa, NIF, data, total e etiquetas — é guardado apenas
no armazenamento privado da aplicação, inacessível a outras aplicações:

- imagens das páginas em `files/documents/<id>/`;
- base de dados SQLite `vcc-scanner.db`, com o índice de pesquisa;
- exportações temporárias na cache da aplicação, apagadas pelo sistema quando necessário.

A aplicação não recolhe identificadores do dispositivo, não usa publicidade, não usa analítica e
não regista relatórios de falhas.

## Processamento de imagem e texto

O reconhecimento de texto, a leitura de códigos e a identificação do idioma usam modelos ML Kit
incluídos na aplicação e executados localmente. As imagens não saem do dispositivo.

A digitalização com a câmara usa o scanner de documentos do Google Play Services. Esse componente
pode ser descarregado do Google Play na primeira utilização, o que exige ligação à Internet. Durante
a digitalização, as imagens são tratadas pelo Google Play Services no próprio dispositivo. A
utilização desse componente rege-se pela
[Política de Privacidade do Google](https://policies.google.com/privacy). A importação de imagens e
PDF não depende deste componente e funciona sem ligação à Internet.

## Permissões

A aplicação não declara permissões de acesso ao armazenamento nem à câmara. Os ficheiros são
escolhidos pelo seletor do sistema e a câmara é utilizada pelo scanner do Google Play Services, que
pede a permissão em nome próprio quando é chamado.

## Partilha de dados

Só sai do dispositivo aquilo que o utilizador exportar, partilhar ou imprimir explicitamente. O
destino é escolhido pelo utilizador no seletor do sistema e passa a ser regido pela política da
aplicação ou serviço de destino.

## Conservação e eliminação

Os documentos permanecem no dispositivo até serem eliminados pelo utilizador. Eliminar um documento
remove as imagens e os respetivos registos. Desinstalar a aplicação elimina todos os dados: a cópia
de segurança automática do Android está desativada (`android:allowBackup="false"`), pelo que os
documentos não são incluídos em backups do sistema. Exporte o que pretende conservar antes de
desinstalar.

## Menores

A aplicação não se destina a crianças nem trata conscientemente dados de menores.

## Direitos do utilizador

Como os dados nunca saem do dispositivo e não existe qualquer cópia do lado do programador, o acesso,
a retificação, a exportação e a eliminação são feitos diretamente na aplicação, através dos ecrãs de
edição, exportação e eliminação.

## Alterações

Qualquer alteração a esta política é publicada neste ficheiro, no repositório do projeto, com a data
de atualização revista.
