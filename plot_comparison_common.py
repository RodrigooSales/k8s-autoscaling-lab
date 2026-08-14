from plot_helper import MetricSpec

CONFIGURATION_LABELS = {
    "base_1": "Base 1 Repl",
    "base_5": "Base 5 Repl",
    "hpa_std": "HPA Std",
    "hpa_fast": "HPA Fast",
    "csa_h": "CSA H",
    "csa_hq_25": "CSA HQ 25",
    "csa_hq_50": "CSA HQ 50",
    "csa_java_h": "CSA Java H",
    "csa_java_hq_25": "CSA Java HQ 25",
    "csa_java_hq_50": "CSA Java HQ 50",
    "base_1500": "Base 1 Repl 1.5 CPU",
    "vpa": "VPA",
    "csa_v": "CSA V",
    "csa_vq": "CSA VQ",
    "csa_java_v": "CSA Java V",
    "csa_java_vq": "CSA Java VQ",
}

CONFIGURATION_COLORS = {
    "base_1": "#4C78A8",
    "base_5": "#72B7B2",
    "base_1500": "#9ECAE9",
    "hpa_std": "#F58518",
    "hpa_fast": "#FFBF79",
    "vpa": "#54A24B",
    "csa_h": "#E45756",
    "csa_hq_25": "#D62728",
    "csa_hq_50": "#FF7F7F",
    "csa_v": "#B22222",
    "csa_vq": "#FB6A4A",
    "csa_java_h": "#7B61FF",
    "csa_java_hq_25": "#9467BD",
    "csa_java_hq_50": "#C5B0D5",
    "csa_java_v": "#6A3D9A",
    "csa_java_vq": "#B279A2",
}

COMPARISON_METRICS = [
    MetricSpec("pods_mean", "Media de Pods (número de réplicas)"),
    MetricSpec("cpu_limits_mean", "Média do limite de CPU (fração de CPU)"),
    MetricSpec("response_time_mean", "Tempo medio das respostas (ms)"),
    MetricSpec("response_size_mean", "Tamanho medio das respostas (bytes)"),
    MetricSpec("success_rate", "Respostas 200 (%)", percent_axis=True),
    MetricSpec(
        "slo_breach_success_rate",
        "Requisicoes acima do SLO, apenas sucesso (%)",
        percent_axis=True,
    ),
]

