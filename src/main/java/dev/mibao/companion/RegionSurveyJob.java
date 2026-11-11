package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;

final class RegionSurveyJob extends Actions.Job {
  private final RegionSurvey survey;

  RegionSurveyJob(JsonObject args, boolean lights, Consumer<String> done) {
    super(done);
    survey = new RegionSurvey(args, lights);
  }

  @Override void tick() {
    Actions.status = "分帧查询已加载区域";
    if (survey.advance()) finish(survey.result().toString());
  }
}
