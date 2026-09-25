# CSA Go nas visualizações do notebook

Data: 2026-09-25. Branch: `csa-go`. Revisão inicial:
`df019c3c25ed897c53457dba6ed65d5950f8d273`.
Diretório de trabalho: `k8s-autoscaling-lab/autoscalers`, salvo indicação da raiz do laboratório.

## Dados e escopo

Foram encontrados 1.050 CSVs de execução: 21 cenários com 50 arquivos cada, incluindo
250 arquivos Go (H, HQ 25, HQ 50, V e VQ), numerados de `01` a `50`.
O notebook [leitura_resultados.ipynb](../../../tests/notebooks/leitura_resultados.ipynb)
já descobre os CSVs automaticamente, mas faltavam rótulos e cores explícitos para Go.
Além disso, a classificação genérica `csa_` agrupava as bolhas Go com as Python.

Mudanças:

- [plot_comparison_common.py](../../../plot_comparison_common.py): nomes `CSA Go ...`
  e cinco tons de ciano, compartilhados pelo notebook e pelos gráficos agregados.
- [plot_comparison_bubble.py](../../../plot_comparison_bubble.py): categoria `CSA Go`,
  identificada antes de `csa_`, com cor própria e posição na legenda.
- Notebook: explicação dos perfis Go e atualização dos resultados e das oito figuras.
  A largura dos boxplots acompanha a quantidade de cenários e a legenda estatística fica
  fora da área dos eixos, com espaço reservado pelo layout. Na inspeção inicial das imagens
  com 21 cenários, a posição anterior da legenda se sobrepunha a alguns rótulos.
- [test_plot_comparison.py](../../../tests/test_plot_comparison.py): regressão da
  descoberta/rotulagem, classificação de Go e preservação das cores Python/Java nas bolhas.

Os cálculos de métricas, SLO, agregação e Pareto seguem as funções existentes.
Nenhuma nova carga foi executada. Os CSVs brutos e os diretórios `csa/` e `csa-java/`
não foram editados.

## Verificação

A suíte tem três testes. Antes da correção, houve oito falhas incluindo subtestes:
rótulos genéricos, classificação como Python e alteração das cores das referências ao
acrescentar Go. Depois da correção, os três testes passaram. A retirada temporária das
mudanças nos dois módulos reproduziu as mesmas falhas; a restauração voltou a passar.

O notebook foi reexecutado e salvo com os oito gráficos: seis métricas compartilhadas,
violações de SLO por tempo/status e bolhas. Cada perfil Go possui 50 amostras válidas
em todas as sete métricas; nenhum perfil Go foi omitido em qualquer figura. As tabelas
incluem os 21 cenários e 1.050 execuções. A validação de formato Jupyter passou, sem células
com erro. A conferência visual confirmou rótulos e legendas legíveis após o ajuste de layout.

## Ambiente

Python 3.13.11, pandas 2.3.3, matplotlib 3.10.6, seaborn 0.13.2 e ipykernel 7.3.0.
Para reexecutar e salvar o notebook sem abrir uma interface Jupyter, foi instalado
`nbclient 0.11.0` na `.venv` existente, com `nbformat 5.11.1` e suas dependências.
Nenhum requisito de execução dos adaptadores foi alterado.

## Comandos e investigação

Os comandos abaixo registram as leituras e validações desta alteração. As operações de
edição acrescentaram apenas os rótulos, cores, categoria, documentação e testes descritos acima.

### Leitura 1

```sh
pwd && git status --short && rg --files -g 'AGENTS.md' -g '*.ipynb' -g '*plot*.py' -g '*graph*.py' -g '*notebook*' ..
```

Notebook localizado em tests/notebooks/leitura_resultados.ipynb; estado inicial limpo, exceto submódulo vagrant-kubeadm-kubernetes já modificado.

### Leitura 2

```sh
rg --files ../tests/results | head -n 35; rg --files csa-go/docs; rg --files -g 'AGENTS.md' /home/rodrigo/Work/pesquisa/custom-self-adapter 2>/dev/null
```

Resultados e documentação localizados; instruções da raiz do laboratório e do repositório pai lidas.

### Leitura 3

```sh
cat ../../AGENTS.md ../AGENTS.md && git rev-parse HEAD && git branch --show-current
```

Revisão df019c3c25ed897c53457dba6ed65d5950f8d273, branch csa-go.

### Leitura 4

```sh
python3 - <<'PY'
import json
from pathlib import Path
path = Path('../tests/notebooks/leitura_resultados.ipynb')
book = json.loads(path.read_text())
print('Notebook:', path, 'metadata:', book['metadata'])
for index, cell in enumerate(book['cells']):
    print(f'\nCELL {index} ({cell["cell_type"]}) outputs={len(cell.get("outputs", []))}')
    print(''.join(cell['source']))
PY
```

27 células, oito gráficos, descoberta automática e cálculo pelas funções compartilhadas. Nenhuma célula dispara carga ou usa Kubernetes.

### Leitura 5

```sh
rg -n 'csa|CSA|SCENARIO|label|order|palette|color|glob|results|argparse' ../plot*.py ../tests -g '*.py' -g '*.md'; cat ../requirements.txt; sed -n '1,260p' ../plot_comparison_common.py
```

Ausência dos cinco rótulos e cores Go; categoria genérica csa_ captura CSA Go no gráfico de bolhas.

### Leitura 6

```sh
python3 - <<'PY'
import json
from collections import Counter
from pathlib import Path
book = json.loads(Path('../tests/notebooks/leitura_resultados.ipynb').read_text())
for index, cell in enumerate(book['cells']):
    print(f'\nCELL {index} ({cell["cell_type"]})')
    print(''.join(cell['source']))
files = list(Path('../tests/results').glob('*.csv'))
counts = Counter('_'.join(path.stem.split('_')[2:]) for path in files if path.stem[:2].isdigit())
print('\nCSV COUNTS:', dict(sorted(counts.items())))
print('Notebook bytes:', Path('../tests/notebooks/leitura_resultados.ipynb').stat().st_size)
PY
```

21 cenários com 50 CSVs cada. CSA Go H, HQ25, HQ50, V e VQ somam 250 CSVs. Notebook original com 998.544 bytes.

### Leitura 7

```sh
sed -n '1,230p' ../plot_comparison_bubble.py && sed -n '230,390p' ../plot_comparison_bubble.py && sed -n '1,110p' ../plot_helper.py && sed -n '260,355p' ../plot_helper.py && sed -n '1,90p' ../plot_comparison_aggregated.py && rg --files ../tests -g '*test*.py' -g '*.sh'
```

Nenhum teste Python de visualização preexistente; rg retornou 1 por ausência de correspondências. Fontes confirmam seleção automática, métricas comuns e cores por categoria.

## Execução das verificações

### Verificação 1

Diretório: `k8s-autoscaling-lab/autoscalers/`.

```sh
../.venv/bin/python - <<'PY'
import importlib.util
import sys
print('Python:', sys.version)
for module in ('nbformat', 'nbclient', 'ipykernel', 'matplotlib', 'pandas', 'seaborn'):
    spec = importlib.util.find_spec(module)
    print(module, spec.origin if spec else 'NOT INSTALLED')
PY
rg --files ../tests -g '*.py'
git ls-files ../tests/results/compare_bubble_notebook.png ../tests/notebooks/leitura_resultados.ipynb
sed -n '1,25p' ../tests/notebooks/leitura_resultados.ipynb
```

Confirmado Python 3.13.11 e bibliotecas de análise instaladas. nbclient e nbformat estavam ausentes; ipykernel já estava disponível. Notebook e PNG de bolhas são arquivos versionados.

### Verificação 2

Diretório: `k8s-autoscaling-lab/autoscalers/`.

```sh
rg --files .. -g '*test*.py' -g '*jupyter*' -g '*pytest*' -g '*nbconvert*' -g '*comparison*'
sed -n '1,160p' ../.gitignore
ls -lh ../tests/results/compare_bubble_notebook.png
```

Não havia testes Python de visualização no laboratório. O notebook usa os módulos compartilhados da raiz.

### Verificação 3

Diretório: raiz de `k8s-autoscaling-lab/`.

```sh
MPLBACKEND=Agg .venv/bin/python -m unittest discover -s tests -p 'test_plot_comparison.py' -v
```

RED: três testes executados, oito falhas contando subtestes. Os cinco nomes Go usavam capitalização genérica; a categoria Go retornava CSA; as cores Python mudavam ao incluir Go.

### Verificação 4

Diretório: `k8s-autoscaling-lab/autoscalers/`.

```sh
../.venv/bin/python -m pip --version; command -v uv
```

pip 25.3 disponível na virtualenv. uv ausente; foi usado pip.

### Verificação 5

Diretório: raiz de `k8s-autoscaling-lab/`.

```sh
MPLBACKEND=Agg .venv/bin/python -m unittest discover -s tests -p 'test_plot_comparison.py' -v
```

GREEN: três testes aprovados.

### Verificação 6

Diretório: raiz de `k8s-autoscaling-lab/`.

```sh
python3 - <<'PY'
from pathlib import Path
import os
import subprocess

paths = [Path('plot_comparison_common.py'), Path('plot_comparison_bubble.py')]
changed = {path: path.read_bytes() for path in paths}
command = ['.venv/bin/python', '-m', 'unittest', 'discover', '-s', 'tests', '-p', 'test_plot_comparison.py', '-v']
environment = {**os.environ, 'MPLBACKEND': 'Agg'}
try:
    for path in paths:
        path.write_bytes(subprocess.check_output(['git', 'show', f'HEAD:{path}']))
    result = subprocess.run(command, env=environment, capture_output=True, text=True)
    print(result.stderr[-200:])
    assert result.returncode == 1 and 'FAILED (failures=8)' in result.stderr, result.stderr
    print('Original production code: RED confirmed')
finally:
    for path, content in changed.items():
        path.write_bytes(content)
subprocess.run(command, env=environment, check=True)
print('Updated production code restored: GREEN confirmed')
PY
```

Retirada temporária somente dos dois módulos de visualização reproduziu oito falhas. As mudanças foram restauradas em finally e os três testes voltaram a passar.

### Verificação 7

Diretório: `k8s-autoscaling-lab/autoscalers/`.

```sh
../.venv/bin/python -m pip install nbclient
```

nbclient 0.11.0 e nbformat 5.11.1 instalados na virtualenv, com suas dependências.

### Verificação 8

Diretório: raiz de `k8s-autoscaling-lab/`.

```sh
MPLBACKEND=module://matplotlib_inline.backend_inline .venv/bin/python -u - <<'PY'
import base64
from importlib.metadata import version
from pathlib import Path
import tempfile

import nbformat
from nbclient import NotebookClient

path = Path('tests/notebooks/leitura_resultados.ipynb').resolve()
book = nbformat.read(path, as_version=4)
check = nbformat.v4.new_code_cell("""
go_configurations = {f"csa_go_{profile}" for profile in ("h", "hq_25", "hq_50", "v", "vq")}
expected_runs = {f"{run:02d}" for run in range(1, 51)}
for configuration in go_configurations:
    selected = run_df[run_df["configuration"] == configuration]
    assert len(selected) == 50, (configuration, len(selected))
    assert set(selected["run"]) == expected_runs, configuration
    assert configuration in configuration_palette, configuration
    assert configuration in set(bubble_df["configuration"]), configuration
for metric in COMPARISON_METRICS:
    assert go_configurations <= set(plot_df.loc[plot_df["metric"] == metric.key, "configuration"]), metric.key
assert go_configurations <= set(slo_plot_df["configuration"])
assert len(run_df) == 1050
assert len(configurations_df) == len(bubble_df) == 21
print("Kernel:", sys.executable)
print("CSV files:", len(run_df), "Configurations:", len(configurations_df))
print(availability_df[availability_df["configuration"].isin(go_configurations)].to_string(index=False))
print("Valid Go samples per metric:")
print(summary_df[summary_df["configuration"].isin(go_configurations)].pivot(index="configuration", columns="metric", values="runs").to_string())
print("Go SLO violation samples:", slo_summary_df[slo_summary_df["configuration"].isin(go_configurations)]["runs"].tolist())
print("All five Go profiles present in all eight charts.")
""")
book.cells.append(check)

def progress(cell, cell_index, **kwargs):
    if cell.cell_type == 'code':
        print(f'Cell {cell_index} complete', flush=True)

print({name: version(name) for name in ('nbclient', 'nbformat', 'ipykernel', 'matplotlib', 'pandas', 'seaborn')})
client = NotebookClient(
    book, timeout=1200, kernel_name='python3', record_timing=False,
    resources={'metadata': {'path': str(path.parent)}},
    on_cell_executed=progress,
)
client.execute()
validation = book.cells.pop()
for output in validation.get('outputs', []):
    if output.output_type == 'stream':
        print(output.text)
    elif output.output_type == 'error':
        raise AssertionError(output)
image_cells = [
    index for index, cell in enumerate(book.cells)
    if any('image/png' in output.get('data', {}) for output in cell.get('outputs', []))
]
assert image_cells == [11, 13, 15, 17, 19, 21, 24, 26], image_cells
assert not [
    output for cell in book.cells for output in cell.get('outputs', [])
    if output.output_type == 'error'
]
nbformat.validate(book)
nbformat.write(book, path)
preview_dir = Path(tempfile.mkdtemp(prefix='csa-go-notebook-'))
for index in image_cells:
    for output in book.cells[index].outputs:
        if 'image/png' in output.get('data', {}):
            (preview_dir / f'cell-{index}.png').write_bytes(base64.b64decode(output.data['image/png']))
print('Preview directory:', preview_dir)
print('Notebook saved with eight charts and no execution errors.')
PY
```

Primeira execução integral concluída: 1.050 CSVs, 21 cenários, 250 execuções Go; oito imagens e nenhum erro. Todas as seis métricas compartilhadas e a violação de SLO têm 50 amostras válidas por perfil Go. O kernel efetivo foi o Python da .venv.

### Verificação 9

Diretório: raiz de `k8s-autoscaling-lab/`.

```sh
git diff -- plot_comparison_common.py plot_comparison_bubble.py && git diff --quiet -- autoscalers/csa autoscalers/csa-java && git diff --quiet -- 'tests/results/*.csv'
```

Diff restrito às adições de rótulos, cores e categoria nos módulos de visualização. As árvores Python/Java e os CSVs brutos permaneceram sem diff.

### Verificação 10

Diretório: raiz de `k8s-autoscaling-lab/`.

Reexecutado o comando da verificação 8.

Reexecução após a inspeção visual encontrar a legenda sobre rótulos. O mesmo comando foi usado após ajustar a largura e a posição da legenda no notebook.

Inspeção visual com `view_image`: `/tmp/csa-go-notebook-n2br1ny_/cell-11.png`,
`cell-19.png` e `cell-26.png` (Pods, sucesso HTTP e bolhas). O PNG exportado pelo
notebook é `tests/results/compare_bubble_notebook.png`.

## Conferência final

```sh
git diff --check
git diff --quiet -- autoscalers/csa autoscalers/csa-java 'tests/results/*.csv'
.venv/bin/python - <<'PY'
import json
from pathlib import Path
import subprocess
import nbformat

path = Path('tests/notebooks/leitura_resultados.ipynb')
original = json.loads(subprocess.check_output(['git', 'show', f'HEAD:{path}']))
current = json.loads(path.read_text())
assert len(original['cells']) == len(current['cells']) == 27
changed_sources = [
    index for index, (before, after) in enumerate(zip(original['cells'], current['cells']))
    if before['source'] != after['source']
]
assert changed_sources == [3, 9], changed_sources
images = [
    index for index, cell in enumerate(current['cells'])
    if any('image/png' in output.get('data', {}) for output in cell.get('outputs', []))
]
assert images == [11, 13, 15, 17, 19, 21, 24, 26], images
for cell in current['cells']:
    if cell['cell_type'] == 'code':
        assert cell['execution_count'] is not None
        assert all(output['output_type'] != 'error' for output in cell['outputs'])
nbformat.validate(nbformat.read(path, as_version=4))
assert Path('tests/results/compare_bubble_notebook.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
print('Notebook valid: 27 cells, 8 charts, source edits limited to description and layout.')
print('Exported bubble PNG present. No notebook errors.')
PY
git diff --stat
git status --short
```

Resultado: notebook válido, com as mesmas 27 células e oito saídas PNG, sem erro de
execução. As únicas mudanças nas fontes de células são a descrição dos cenários (célula 3)
e o layout dos boxplots (célula 9). As tabelas e imagens foram recalculadas a partir dos CSVs.
O PNG de bolhas exportado tem 637.255 bytes. Os diffs dos CSVs brutos e dos diretórios
Python/Java estão vazios; `git diff --check` passou. O submódulo de infraestrutura já estava
modificado antes do trabalho e não foi alterado nesta atividade.

A segunda execução integral também passou. Inspeção final com `view_image` em
`/tmp/csa-go-notebook-kwuy0o6c/cell-11.png` e `cell-24.png`: os 21 rótulos estão legíveis,
os cinco cenários Go aparecem em ciano e a legenda estatística não se sobrepõe ao eixo X.
O gráfico de bolhas mantém legenda separada por implementação, incluindo os cinco perfis Go.
