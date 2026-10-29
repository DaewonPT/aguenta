# Aguenta

Assistente de orçamento para Android que lê as notificações de pagamento da **Samsung Wallet**, **Caixa Geral de Depósitos** e **Wise** e recalcula a cada compra quanto ainda podes gastar hoje até ao próximo salário.

- **Radar diário**: teto de hoje = saldo / dias até ao salário, velocidade de gasto e quantos dias aguentas ao ritmo atual.
- **Alertas que não se ignoram**: aos 80% do teto a notificação fica presa até escolheres *Foi Essencial*, *Aguenta! Congelar Gastos* ou *Ajustar Saldo*.
- **Movimentos**: últimos 90 dias, por data, com pesquisa e filtros por categoria.
- **Lições do ciclo passado**: no início de cada ciclo, onde dava para cortar.

## Instalar

1. Descarrega o `aguenta-vX.Y.Z.apk` da [última Release](https://github.com/DaewonPT/aguenta/releases/latest) e instala-o (o Android pede para autorizar instalações desta fonte).
2. Android 13 ou mais recente: em *Definições → Apps → Aguenta → ⋮*, toca em **Permitir definições restritas**.
3. Abre o Aguenta e dá **acesso às notificações**.
4. No separador **Ciclo**, indica o dia e o valor do salário e define o saldo atual.

As versões novas aparecem dentro da app: um toque descarrega-as e o Android pede confirmação para instalar.

## Privacidade

Os movimentos ficam só no telemóvel, numa base de dados cifrada (SQLCipher) com a chave no Android Keystore. A única ligação à internet é para ver se há uma versão nova aqui no GitHub.

---

Pixel Lusitano · [pixellusitano.com](https://pixellusitano.com) · contact@pixellusitano.com
