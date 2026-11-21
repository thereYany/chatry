/**
 * 通用 LLM 调用框架：provider 抽象、路由、超时/重试/熔断/限流、流式、可观测与成本归因。
 *
 * <p>业务无关：不认识客服/RAG/内容等垂直场景；只依赖 platform 与 contracts。
 * 计费口径不在此模块（由 metering 负责），本模块只产出 usage 与调用记录。</p>
 */
@org.springframework.modulith.ApplicationModule(displayName = "LLM", allowedDependencies = {"platform", "contracts"})
package com.wherelee.base.modules.llm;
