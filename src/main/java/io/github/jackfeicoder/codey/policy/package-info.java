/**
 * 已实现 ask/规则 auto/full 预设、独立审查接口、人工批准与会话文件授权/拒绝记录。
 * PLAN 只读硬约束先于审批；批准在准备后的工具操作执行前落实，不依赖模型口头声明。
 * 文件范围仍由内置工具检查；进程/网络沙箱和持久审计后续接入。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.policy;
