package com.khartec.waltz.service.scenario;

import com.khartec.waltz.data.scenario.ScenarioDao;
import com.khartec.waltz.model.scenario.Scenario;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;

import static com.khartec.waltz.common.Checks.checkNotNull;

@Service
public class ScenarioService {

    private final ScenarioDao scenarioDao;


    @Autowired
    public ScenarioService(ScenarioDao scenarioDao) {
        checkNotNull(scenarioDao, "scenarioDao cannot be null");
        this.scenarioDao = scenarioDao;
    }


    public Scenario getById(long id) {
        return scenarioDao.getById(id);
    }


    public Collection<Scenario> findForRoadmapId(long roadmapId) {
        return scenarioDao.findForRoadmapId(roadmapId);
    }

}
