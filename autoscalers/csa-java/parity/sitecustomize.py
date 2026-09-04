import builtins
import os

from kubernetes import client, config


def load_fake_cluster_config():
    configuration = client.Configuration()
    configuration.host = os.environ["CSA_KUBERNETES_URL"]
    configuration.verify_ssl = False
    client.Configuration.set_default(configuration)


config.load_incluster_config = load_fake_cluster_config

_open = builtins.open


def open_test_config(file, *args, **kwargs):
    if os.fspath(file) == "/config.yaml":
        file = os.environ["CSA_CONFIG_PATH"]
    return _open(file, *args, **kwargs)


builtins.open = open_test_config
