package com.whatswater.curd.project.module.workflow.flowLinkRelation;


import com.whatswater.curd.project.common.CrudUtils;
import com.whatswater.curd.project.common.Page;
import com.whatswater.curd.project.common.PageResult;
import com.whatswater.curd.project.module.workflow.flowLink.FlowLink;
import com.whatswater.curd.project.module.workflow.flowLink.FlowLinkService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.assist.SQLExecute;
import io.vertx.ext.sql.assist.SqlAssist;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.mysqlclient.MySQLPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class FlowLinkRelationService {
    private final FlowLinkRelationSQL flowLinkRelationSQL;
    private FlowLinkService flowLinkService;

    public FlowLinkRelationService(MySQLPool pool) {
        this.flowLinkRelationSQL = new FlowLinkRelationSQL(SQLExecute.createMySQL(pool));
    }

    public Future<PageResult<FlowLinkRelation>> search(Page page, FlowLinkRelationQuery query) {
        SqlAssist sqlAssist = query.toSqlAssist();
        sqlAssist.setStartRow(page.getOffset());
        sqlAssist.setRowSize(page.getLimit());

        return flowLinkRelationSQL.getCount(sqlAssist).compose(total -> {
            if (CrudUtils.notZero(total)) {
                return flowLinkRelationSQL.selectAll(sqlAssist).map(list -> PageResult.of(list.stream().map(FlowLinkRelation::new).collect(Collectors.toList()), page, total));
            } else {
                return Future.succeededFuture(PageResult.empty());
            }
        });
    }

    public Future<FlowLinkRelation> getById(Long flowLinkRelationId) {
        Future<JsonObject> result = flowLinkRelationSQL.selectById(flowLinkRelationId);
        return result.map(json -> {
            if (json == null) {
                return null;
            }
            return new FlowLinkRelation(json);
        });
    }

    public Future<Long> insert(FlowLinkRelation flowLinkRelation) {
        return flowLinkRelationSQL.insertNonEmptyGeneratedKeys(flowLinkRelation, MySQLClient.LAST_INSERTED_ID);
    }

    public Future<Integer> update(FlowLinkRelation flowLinkRelation) {
        return flowLinkRelationSQL.updateNonEmptyById(flowLinkRelation);
    }

    // todo ??????????????????routeName -> FlowLink
    /**
     * ?????????????????????
     * @param flowLink ????????????
     * @return ??????????????????
     */
    public Future<List<FlowLink>> queryNextLink(FlowLink flowLink) {
        return queryNextLink(flowLink.getId());
    }

    /**
     * ????????????Id????????????????????????Relation
     * @param flowLinkId ??????Id
     * @return FlowLinkRelation??????
     */
    public Future<List<FlowLinkRelation>> queryNextLinkRelation(long flowLinkId) {
        SqlAssist sqlAssist = FlowLinkRelation.startLinkIdSqlAssist(flowLinkId);
        return flowLinkRelationSQL.selectAll(sqlAssist).map(list -> {
            if (list == null || list.isEmpty()) {
                return Collections.emptyList();
            }
            return list.stream().map(FlowLinkRelation::new).collect(Collectors.toList());
        });
    }

    /**
     * ????????????Id????????????????????????Id
     * @param flowLinkId ??????Id
     * @return ????????????Id??????
     */
    public Future<List<Long>> queryNextLinkId(long flowLinkId) {
        return queryNextLinkRelation(flowLinkId).map(flowLinkRelations -> {
            List<Long> endLinkIdList = new ArrayList<>(flowLinkRelations.size());
            for (FlowLinkRelation flowLinkRelation: flowLinkRelations) {
                endLinkIdList.add(flowLinkRelation.getEndLinkId());
            }
            return endLinkIdList;
        });
    }

    /**
     * ????????????Id?????????????????????
     * @param flowLinkId ??????Id
     * @return ??????????????????
     */
    public Future<List<FlowLink>> queryNextLink(long flowLinkId) {
        return queryNextLinkId(flowLinkId).compose(flowLinkService::listByIds);
    }

    /**
     * ????????????Id????????????????????????Relation
     * @param flowLinkId ??????Id
     * @return FlowLinkRelation??????
     */
    public Future<List<FlowLinkRelation>> queryPrevLinkRelation(long flowLinkId) {
        SqlAssist sqlAssist = FlowLinkRelation.endLinkIdSqlAssist(flowLinkId);
        return flowLinkRelationSQL.selectAll(sqlAssist).map(list -> {
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.stream().map(FlowLinkRelation::new).collect(Collectors.toList());
        });
    }

    /**
     * ????????????Id??????????????????????????????Id
     * @param flowLinkId ??????Id
     * @return ??????Id??????
     */
    public Future<List<Long>> queryPrevLinkId(long flowLinkId) {
        return queryPrevLinkRelation(flowLinkId).map(flowLinkRelations -> {
            List<Long> endLinkIdList = new ArrayList<>(flowLinkRelations.size());
            for (FlowLinkRelation flowLinkRelation: flowLinkRelations) {
                endLinkIdList.add(flowLinkRelation.getEndLinkId());
            }
            return endLinkIdList;
        });
    }

    /**
     * ????????????Id?????????????????????
     * @param flowLinkId ??????Id
     * @return ???????????????
     */
    public Future<List<FlowLink>> queryPrevLink(long flowLinkId) {
        return queryPrevLinkId(flowLinkId).compose(flowLinkService::listByIds);
    }

    public void setFlowLinkService(FlowLinkService flowLinkService) {
        this.flowLinkService = flowLinkService;
    }
}
