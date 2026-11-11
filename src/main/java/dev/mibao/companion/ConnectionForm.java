package dev.mibao.companion;

/** Validate the whole draft before committing any live setting. Never prints a secret. */
public record ConnectionForm(
    String endpoint,
    String model,
    String key,
    String protocol,
    int output,
    int turns,
    int idle,
    int port) {
  public ConnectionForm {
    endpoint = endpoint.trim();
    model = model.trim();
    key = key.trim();
    protocol = ProviderWire.protocol(protocol);
    if (endpoint.isBlank() || model.isBlank()) throw new IllegalArgumentException("API地址和模型名不能为空");
    try {
      ProviderWire.endpoint(endpoint, model, protocol);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("API地址或模型格式与所选协议不符，请检查草稿");
    }
    if (output < 256
        || output > 12000
        || turns < 1
        || turns > 96
        || idle < 15
        || idle > 3600
        || port < 1024
        || port > 65535)
      throw new IllegalArgumentException("参数范围：输出256..12000，基础轮数1..96，间隔15..3600，端口1024..65535");
  }

  @Override
  public String toString() {
    return "ConnectionForm[redacted]";
  }
}
