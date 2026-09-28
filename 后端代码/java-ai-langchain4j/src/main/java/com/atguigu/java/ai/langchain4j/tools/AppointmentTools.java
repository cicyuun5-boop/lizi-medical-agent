package com.atguigu.java.ai.langchain4j.tools;

import com.atguigu.java.ai.langchain4j.entity.Appointment;
import com.atguigu.java.ai.langchain4j.service.AppointmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AppointmentTools {

    private static final Logger log = LoggerFactory.getLogger(AppointmentTools.class);

    @Autowired
    private AppointmentService appointmentService;

    @Tool(name="预约挂号", value = "根据参数，先执行工具方法queryDepartment查询是否可预约，并直接给用户回答是否可预约，并让用户确认所有预约信息，用户确认后再进行预约。如果用户没有提供具体的医生姓名，请从向量存储中找到一位医生。")
    public String bookAppointment(Appointment appointment){

        //查找数据库中是否包含对应的预约记录
        Appointment appointmentDB = appointmentService.getOne(appointment);

        if(appointmentDB == null){
            appointment.setId(null);//防止大模型幻觉设置了id
            if(appointmentService.save(appointment)){
                return "预约成功，并返回预约详情";
            }else{
                return "预约失败";
            }
        }

        return "您在相同的科室和时间已有预约";
    }

    @Tool(name="取消预约挂号", value = "根据参数，查询预约是否存在，如果存在则删除预约记录并返回取消预约成功，否则返回取消预约失败")
    public String cancelAppointment(Appointment appointment){

        Appointment appointmentDB = appointmentService.getOne(appointment);
        if(appointmentDB != null){
            //删除预约记录
            if(appointmentService.removeById(appointmentDB.getId())){
                return "取消预约成功";
            }else{
                return "取消预约失败";
            }
        }

        //取消失败
        return "您没有预约记录，请核对预约科室和时间";
    }


    @Tool(name = "查询是否有号源", value="根据科室名称，日期，时间和医生查询是否有号源，并返回给用户")
    public boolean queryDepartment(
            @P(value = "科室名称") String name,
            @P(value = "日期") String date,
            @P(value = "时间，可选值：上午、下午") String time,
            @P(value = "医生名称", required = false) String doctorName
    ) {

        // 没给医生姓名时项目里没有排班表可查，只能按「该时段仍有号」放行；
        // 真正的占位约束由 bookAppointment 的重复预约校验兜底。
        if (doctorName == null || doctorName.isBlank()) {
            log.info("查询号源（未指定医生）：科室={}，日期={}，时段={}", name, date, time);
            return true;
        }

        // 指定了医生：该医生在同一科室 + 日期 + 时段已有预约，即视为该医生该时段约满
        LambdaQueryWrapper<Appointment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Appointment::getDepartment, name);
        queryWrapper.eq(Appointment::getDate, date);
        queryWrapper.eq(Appointment::getTime, time);
        queryWrapper.eq(Appointment::getDoctorName, doctorName);

        long booked = appointmentService.count(queryWrapper);
        log.info("查询号源：科室={}，日期={}，时段={}，医生={}，已约={}", name, date, time, doctorName, booked);

        // ponytail: 项目没有医生排班表，判断不了「该科室该时段到底有几位医生出诊」，
        // 所以只落地了「同一医生同一时段不被重复占用」这一条真实约束。
        // 升级路径：加 doctor_schedule(department, doctor_name, date, time, capacity) 表，
        // 未指定医生时先查出诊医生列表，再逐个判断是否约满。
        return booked == 0;
    }

}