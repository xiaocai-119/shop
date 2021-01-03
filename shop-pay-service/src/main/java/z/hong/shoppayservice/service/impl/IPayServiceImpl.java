package z.hong.shoppayservice.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import z.hong.shopapi.api.IPayService;
import z.hong.shopcommon.constant.ShopCode;
import z.hong.shopcommon.exception.CastException;
import z.hong.shopcommon.utils.IDWorker;
import z.hong.shoppayservice.mapper.TradeMqProducerTempMapper;
import z.hong.shoppayservice.mapper.TradePayMapper;
import z.hong.shoppojo.entity.Result;
import z.hong.shoppojo.pojo.TradeMqProducerTemp;
import z.hong.shoppojo.pojo.TradePay;
import z.hong.shoppojo.pojo.TradePayExample;

import java.util.Date;

@Component
@Service(interfaceClass = IPayService.class)
public class IPayServiceImpl implements IPayService {

    @Autowired
    private IDWorker idWorker;
    @Autowired
    private TradePayMapper tradePayMapper;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    @Autowired
    private TradeMqProducerTempMapper mqProducerTempMapper;
    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;


    @Value("${rocketmq.producer.group}")
    private String groupName;
    @Value("${mq.topic}")
    private String topic;
    @Value("${mq.pay.tag}")
    private String tag;

    private Logger logger= LoggerFactory.getLogger(IPayServiceImpl.class);
    @Override
    public Result createPayment(TradePay tradePay) {
        if(tradePay==null || tradePay.getOrderId()==null){
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }

        //1.判断订单支付状态
        TradePayExample example = new TradePayExample();
        TradePayExample.Criteria criteria = example.createCriteria();
        criteria.andOrderIdEqualTo(tradePay.getOrderId());
        criteria.andIsPaidEqualTo(ShopCode.SHOP_PAYMENT_IS_PAID.getCode());
        int r = tradePayMapper.countByExample(example);
        if(r>0){
            CastException.cast(ShopCode.SHOP_PAYMENT_IS_PAID);
        }
        //2.设置订单的状态为未支付
        tradePay.setIsPaid(ShopCode.SHOP_ORDER_PAY_STATUS_NO_PAY.getCode());
        //3.保存支付订单
        tradePay.setPayId(idWorker.nextId());
        tradePayMapper.insert(tradePay);

        return new Result(ShopCode.SHOP_SUCCESS.getSuccess(), ShopCode.SHOP_SUCCESS.getMessage());
    }

    @Override
    public Result callbackPayment(TradePay tradePay) {
        //1. 判断用户支付状态
        if(tradePay.getIsPaid().intValue()==ShopCode.SHOP_ORDER_PAY_STATUS_IS_PAY.getCode().intValue()){
            //2. 更新支付订单状态为已支付
            Long payId = tradePay.getPayId();
            TradePay pay = tradePayMapper.selectByPrimaryKey(payId);
            //判断支付订单是否存在
            if(pay==null){
                CastException.cast(ShopCode.SHOP_PAYMENT_NOT_FOUND);
            }
            pay.setIsPaid(ShopCode.SHOP_ORDER_PAY_STATUS_IS_PAY.getCode());
            int r = tradePayMapper.updateByPrimaryKeySelective(pay);
            logger.info("支付订单状态改为已支付");
            if(r==1){
                //3. 创建支付成功的消息
                TradeMqProducerTemp tradeMqProducerTemp = new TradeMqProducerTemp();
                tradeMqProducerTemp.setId(String.valueOf(idWorker.nextId()));
                tradeMqProducerTemp.setGroupName(groupName);
                tradeMqProducerTemp.setMsgTopic(topic);
                tradeMqProducerTemp.setMsgTag(tag);
                tradeMqProducerTemp.setMsgKey(String.valueOf(tradePay.getPayId()));
                tradeMqProducerTemp.setMsgBody(JSON.toJSONString(tradePay));
                tradeMqProducerTemp.setCreateTime(new Date());
                //4. 将消息持久化数据库
                mqProducerTempMapper.insert(tradeMqProducerTemp);
                logger.info("将支付成功消息持久化到数据库");

                //在线程池中进行处理
                threadPoolTaskExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        //5. 发送消息到MQ
                        SendResult result = null;
                        try {
                            result = sendMessage(topic, tag, String.valueOf(tradePay.getPayId()), JSON.toJSONString(tradePay));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        if(result.getSendStatus().equals(SendStatus.SEND_OK)){
                            logger.info("消息发送成功");
                            //6. 等待发送结果,如果MQ接受到消息,删除发送成功的消息
                            mqProducerTempMapper.deleteByPrimaryKey(tradeMqProducerTemp.getId());
                            logger.info("持久化到数据库的消息删除");
                        }
                    }
                });

            }
            return new Result(ShopCode.SHOP_SUCCESS.getSuccess(),ShopCode.SHOP_SUCCESS.getMessage());
        }else{
            CastException.cast(ShopCode.SHOP_PAYMENT_PAY_ERROR);
            return new Result(ShopCode.SHOP_FAIL.getSuccess(),ShopCode.SHOP_FAIL.getMessage());
        }
    }
    /**
     * 发送支付成功消息
     * @param topic
     * @param tag
     * @param key
     * @param body
     */
    private SendResult sendMessage(String topic, String tag, String key, String body) throws InterruptedException, RemotingException, MQClientException, MQBrokerException {
        if(StringUtils.isEmpty(topic)){
            CastException.cast(ShopCode.SHOP_MQ_TOPIC_IS_EMPTY);
        }
        if(StringUtils.isEmpty(body)){
            CastException.cast(ShopCode.SHOP_MQ_MESSAGE_BODY_IS_EMPTY);
        }
        Message message = new Message(topic,tag,key,body.getBytes());
        SendResult sendResult = rocketMQTemplate.getProducer().send(message,10000);
        return sendResult;
    }
}