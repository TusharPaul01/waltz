/*
 * Waltz - Enterprise Architecture
 * Copyright (C) 2016, 2017, 2018, 2019 Waltz open source project
 * See README.md for more information
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific
 *
 */

package com.khartec.waltz.web.endpoints.api;


import com.khartec.waltz.model.assessment_rating.AssessmentGroupedEntities;
import com.khartec.waltz.service.assessment_rating.AssessmentRatingViewService;
import com.khartec.waltz.web.ListRoute;
import com.khartec.waltz.web.endpoints.Endpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.khartec.waltz.common.Checks.checkNotNull;
import static com.khartec.waltz.web.WebUtilities.*;
import static com.khartec.waltz.web.endpoints.EndpointUtilities.postForList;


@Service
public class AssessmentRatingViewEndpoint implements Endpoint {

    private static final String BASE_URL = mkPath("api", "assessment-rating-view");

    private final AssessmentRatingViewService assessmentRatingViewService;


    @Autowired
    public AssessmentRatingViewEndpoint(AssessmentRatingViewService assessmentRatingViewService) {

        checkNotNull(assessmentRatingViewService, "assessmentRatingViewService cannot be null");

        this.assessmentRatingViewService = assessmentRatingViewService;
    }


    @Override
    public void register() {
        String findGroupedByDefinitionAndOutcomePath = mkPath(BASE_URL, "kind", ":kind", "grouped");

        ListRoute<AssessmentGroupedEntities> findGroupedByDefinitionAndOutcomeRoute = (req, res) -> {
            List<Long> entityIds = readIdsFromBody(req);
            return assessmentRatingViewService.findGroupedByDefinitionAndOutcomes(getKind(req), entityIds);
        };

        postForList(findGroupedByDefinitionAndOutcomePath, findGroupedByDefinitionAndOutcomeRoute);
    }

}
