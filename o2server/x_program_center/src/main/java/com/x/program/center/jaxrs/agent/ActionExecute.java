package com.x.program.center.jaxrs.agent;

import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.SimpleScriptContext;

import com.x.base.core.container.EntityManagerContainer;
import com.x.base.core.container.factory.EntityManagerContainerFactory;
import com.x.base.core.project.cache.Cache.CacheCategory;
import com.x.base.core.project.cache.Cache.CacheKey;
import com.x.base.core.project.cache.CacheManager;
import com.x.base.core.project.http.ActionResult;
import com.x.base.core.project.http.EffectivePerson;
import com.x.base.core.project.jaxrs.WoId;
import com.x.base.core.project.logger.Logger;
import com.x.base.core.project.logger.LoggerFactory;
import com.x.base.core.project.script.AbstractResources;
import com.x.base.core.project.script.ScriptFactory;
import com.x.base.core.project.tools.DateTools;
import com.x.base.core.project.webservices.WebservicesClient;
import com.x.organization.core.express.Organization;
import com.x.program.center.ThisApplication;
import com.x.program.center.core.entity.Agent;

class ActionExecute extends BaseAction {

	private static Logger logger = LoggerFactory.getLogger(ActionExecute.class);

	private static final CopyOnWriteArrayList<String> LOCK = new CopyOnWriteArrayList<>();

	ActionResult<Wo> execute(EffectivePerson effectivePerson, String flag) throws Exception {
		try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
			ActionResult<Wo> result = new ActionResult<>();
			Agent agent = emc.flag(flag, Agent.class);
			if (null == agent) {
				throw new ExceptionAgentNotExist(flag);
			}
			if (LOCK.contains(agent.getId())) {
				throw new ExceptionAgentLastNotEnd(agent.getId(), agent.getName(), agent.getAlias(),
						DateTools.format(agent.getLastStartTime()));
			} else {
				try {
					LOCK.add(agent.getId());
					emc.beginTransaction(Agent.class);
					agent.setLastStartTime(new Date());
					emc.commit();
					ScriptContext scriptContext = new SimpleScriptContext();
					Bindings bindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
					Resources resources = new Resources();
					resources.setEntityManagerContainer(emc);
					resources.setContext(ThisApplication.context());
					resources.setOrganization(new Organization(ThisApplication.context()));
					resources.setApplications(ThisApplication.context().applications());
					resources.setWebservicesClient(new WebservicesClient());
					bindings.put(ScriptFactory.BINDING_NAME_RESOURCES, resources);
					bindings.put(ScriptFactory.BINDING_NAME_APPLICATIONS, ThisApplication.context().applications());

					CacheCategory cacheCategory = new CacheCategory(Agent.class);
					CacheKey cacheKey = new CacheKey(ActionExecute.class, agent.getId());
					CompiledScript compiledScript = null;
					Optional<?> optional = CacheManager.get(cacheCategory, cacheKey);
					if (optional.isPresent()) {
						compiledScript = (CompiledScript)optional.get();
					}else {
						compiledScript = ScriptFactory.compile(ScriptFactory.functionalization(agent.getText()));
						CacheManager.put(cacheCategory, cacheKey, compiledScript);
					}

					try {
						ScriptFactory.initialServiceScriptText().eval(scriptContext);
						compiledScript.eval(scriptContext);
					} catch (Exception e) {
						throw new ExceptionAgentEval(e, e.getMessage(), agent.getId(), agent.getName(),
								agent.getAlias(), agent.getText());
					}
				} finally {
					LOCK.remove(agent.getId());
				}
			}
			emc.beginTransaction(Agent.class);
			agent.setLastEndTime(new Date());
			emc.commit();
			Wo wo = new Wo();
			wo.setId(agent.getId());
			result.setData(wo);
			return result;
		}
	}

	public static class Wo extends WoId {

	}

	public static class Resources extends AbstractResources {
		private Organization organization;

		public Organization getOrganization() {
			return organization;
		}

		public void setOrganization(Organization organization) {
			this.organization = organization;
		}

	}

}