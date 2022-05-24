package org.finos.waltz.service.aggregate_overlay_diagram;

import org.finos.waltz.data.aggregate_overlay_diagram.*;
import org.finos.waltz.data.application.ApplicationIdSelectorFactory;
import org.finos.waltz.model.IdSelectionOptions;
import org.finos.waltz.model.aggregate_overlay_diagram.AggregateOverlayDiagram;
import org.finos.waltz.model.overlay_diagram.AssessmentRatingsWidgetDatum;
import org.finos.waltz.model.overlay_diagram.BackingEntityWidgetDatum;
import org.finos.waltz.model.overlay_diagram.CostWidgetDatum;
import org.finos.waltz.model.overlay_diagram.CountWidgetDatum;
import org.finos.waltz.model.overlay_diagram.TargetCostWidgetDatum;
import org.jooq.Record1;
import org.jooq.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Set;

@Service
public class AggregateOverlayDiagramService {

    public static final ApplicationIdSelectorFactory APPLICATION_ID_SELECTOR_FACTORY = new ApplicationIdSelectorFactory();

    private final AggregateOverlayDiagramDao aggregateOverlayDiagramDao;
    private final AppCountWidgetDao appCountWidgetDao;
    private final TargetAppCostWidgetDao targetAppCostWidgetDao;
    private final AppCostWidgetDao appCostWidgetDao;
    private final AppAssessmentWidgetDao appAssessmentWidgetDao;
    private final BackingEntityWidgetDao backingEntityWidgetDao;

    @Autowired
    public AggregateOverlayDiagramService(AggregateOverlayDiagramDao aggregateOverlayDiagramDao,
                                          AppCountWidgetDao appCountWidgetDao,
                                          TargetAppCostWidgetDao targetAppCostWidgetDao,
                                          AppAssessmentWidgetDao appAssessmentWidgetDao,
                                          BackingEntityWidgetDao backingEntityWidgetDao,
                                          AppCostWidgetDao appCostWidgetDao) {
        this.aggregateOverlayDiagramDao = aggregateOverlayDiagramDao;
        this.appCountWidgetDao = appCountWidgetDao;
        this.targetAppCostWidgetDao = targetAppCostWidgetDao;
        this.appCostWidgetDao = appCostWidgetDao;
        this.appAssessmentWidgetDao = appAssessmentWidgetDao;
        this.backingEntityWidgetDao = backingEntityWidgetDao;
    }


    public AggregateOverlayDiagram getById(Long diagramId) {
        return aggregateOverlayDiagramDao.getById(diagramId);
    }


    public Set<AggregateOverlayDiagram> findAll() {
        return aggregateOverlayDiagramDao.findAll();
    }


    public Set<CountWidgetDatum> findAppCountWidgetData(Long diagramId,
                                                        IdSelectionOptions appSelectionOptions,
                                                        LocalDate targetStateDate) {

        Select<Record1<Long>> applicationIdSelector = APPLICATION_ID_SELECTOR_FACTORY.apply(appSelectionOptions);
        return appCountWidgetDao.findWidgetData(diagramId, applicationIdSelector, targetStateDate);
    }


    public Set<TargetCostWidgetDatum> findTargetAppCostWidgetData(Long diagramId,
                                                                  IdSelectionOptions appSelectionOptions,
                                                                  LocalDate targetStateDate) {

        Select<Record1<Long>> applicationIdSelector = APPLICATION_ID_SELECTOR_FACTORY.apply(appSelectionOptions);
        return targetAppCostWidgetDao.findWidgetData(diagramId, applicationIdSelector, targetStateDate);
    }


    public Set<CostWidgetDatum> findAppCostWidgetData(Long diagramId,
                                                      IdSelectionOptions appSelectionOptions,
                                                      Set<Long> costKinds,
                                                      long allocationSchemeId) {

        Select<Record1<Long>> applicationIdSelector = APPLICATION_ID_SELECTOR_FACTORY.apply(appSelectionOptions);
        return appCostWidgetDao.findWidgetData(
                diagramId,
                costKinds,
                allocationSchemeId,
                applicationIdSelector);
    }


    public Set<AssessmentRatingsWidgetDatum> findAppAssessmentWidgetData(Long diagramId,
                                                                         long assessmentId,
                                                                         IdSelectionOptions appSelectionOptions) {

        Select<Record1<Long>> applicationIdSelector = APPLICATION_ID_SELECTOR_FACTORY.apply(appSelectionOptions);
        return appAssessmentWidgetDao.findWidgetData(
                diagramId,
                assessmentId,
                applicationIdSelector);
    }


    public Set<BackingEntityWidgetDatum> findBackingEntityWidgetData(Long diagramId) {
        return backingEntityWidgetDao.findWidgetData(diagramId);
    }


}