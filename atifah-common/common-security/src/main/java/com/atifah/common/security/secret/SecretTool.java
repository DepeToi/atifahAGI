package com.atifah.common.security.secret;

/**
 * 配置密钥加解密命令行工具（维护者本地使用，不参与运行时）。
 *
 * <p>典型用法（在仓库根目录编译后执行，classpath 取 common-security 的 classes 目录或 jar）：</p>
 * <pre>
 *   # 1. 生成主密钥（每环境一把；生产主密钥在 Nacos 生产命名空间下发，禁止写入任何入库文件）
 *   java -cp atifah-common/common-security/target/classes com.atifah.common.security.secret.SecretTool genkey
 *   # 2. 加密配置明文（生成的 ENC() 串粘贴进 application*.yml 或 Nacos 配置）
 *   java -cp ... SecretTool encrypt &lt;主密钥Base64&gt; &lt;明文&gt;
 *   # 3. 核对密文对应的明文（运维验证用）
 *   java -cp ... SecretTool decrypt &lt;主密钥Base64&gt; &lt;ENC(...)&gt;
 * </pre>
 *
 * @author DepeToi
 * @since 1.0.0
 */
public final class SecretTool {

    private SecretTool() {
    }

    /**
     * 命令行入口。
     *
     * @param args 子命令与参数
     */
    public static void main(String[] args) {
        if (args.length == 1 && "genkey".equals(args[0])) {
            System.out.println(SecretCipher.generateMasterKey());
            return;
        }
        if (args.length == 3 && "encrypt".equals(args[0])) {
            System.out.println(SecretCipher.encrypt(SecretCipher.parseMasterKey(args[1]), args[2]));
            return;
        }
        if (args.length == 3 && "decrypt".equals(args[0])) {
            System.out.println(SecretCipher.decrypt(SecretCipher.parseMasterKey(args[1]), args[2]));
            return;
        }
        System.err.println("用法: SecretTool genkey | encrypt <主密钥Base64> <明文> | decrypt <主密钥Base64> <ENC()密文>");
        System.exit(2);
    }
}
